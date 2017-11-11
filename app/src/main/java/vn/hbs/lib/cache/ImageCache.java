/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vn.hbs.lib.cache;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.AsyncTask;
import android.os.Build.VERSION_CODES;
import android.os.Environment;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.util.Log;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.SoftReference;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

import vn.hbs.lib.debug.DebugLog;
import vn.hbs.lib.util.ImageDecoder;
import vn.hbs.lib.util.VersionUtils;

/**
 * This class handles disk and memory caching of bitmaps in conjunction with the
 * HttpRequest class and its subclasses.
 */
public class ImageCache {
    private static final String TAG = ImageCache.class.getSimpleName();

    // Default memory cache size in kilobytes
    private static final int DEFAULT_MEM_CACHE_SIZE = 1024 * 5; // 5MB

    // Default disk cache size in bytes
    private static final int DEFAULT_DISK_CACHE_SIZE = 1024 * 1024 * 10; // 10MB

    // Compression settings when writing images to disk cache
    private static final CompressFormat DEFAULT_COMPRESS_FORMAT = CompressFormat.JPEG;
    private static final int DEFAULT_COMPRESS_QUALITY = 70;
    private static final int DISK_CACHE_INDEX = 0;

    // Constants to easily toggle various caches
    private static final boolean DEFAULT_MEM_CACHE_ENABLED = true;
    private static final boolean DEFAULT_DISK_CACHE_ENABLED = true;

    protected DiskLruCache mDiskLruCache;
    private LruCache<String, BitmapDrawable> mMemoryCache;
    private ImageCacheParams mCacheParams;
    private final Object mDiskCacheLock = new Object();
    private boolean mDiskCacheStarting = true;

    private Set<SoftReference<Bitmap>> mReusableBitmaps;

    /**
     * return An existing retained ImageCache object or a new one if one did not exist
     */
    public static ImageCache getInstance(ImageCacheParams cacheParams) {
        ImageCache instance = null;

        if (cacheParams != null) {
            synchronized (ImageCache.class) {
                cacheParams.setMemCacheSizePercent(0.25f);
                instance = new ImageCache(cacheParams);
            }
        }
        return instance;
    }

    /**
     * Create a new ImageCache object using the specified parameters. This should not be
     * called directly by other classes.
     *
     * @param cacheParams The cache parameters to use to initialize the cache
     */
    private ImageCache(ImageCacheParams cacheParams) {
        mCacheParams = cacheParams;

        // Set up memory cache
        if (mCacheParams.memoryCacheEnabled) {
            // If we're running on Honeycomb or newer, create a set of reusable bitmaps that can be
            // populated into the inBitmap field of BitmapFactory.Options. Note that the set is
            // of SoftReferences which will actually not be very effective due to the garbage
            // collector being aggressive clearing Soft/WeakReferences. A better approach
            // would be to use a strongly references bitmaps, however this would require some
            // balancing of memory usage between this set and the bitmap LruCache. It would also
            // require knowledge of the expected size of the bitmaps. From Honeycomb to JellyBean
            // the size would need to be precise, from KitKat onward the size would just need to
            // be the upper bound (due to changes in how inBitmap can re-use bitmaps).
            if (VersionUtils.hasHoneycomb()) {
                mReusableBitmaps =
                        Collections.synchronizedSet(new HashSet<SoftReference<Bitmap>>());
            }

            mMemoryCache = new LruCache<String, BitmapDrawable>(mCacheParams.memCacheSize) {

                /**
                 * Notify the removed entry that is no longer being cached
                 */
                @Override
                protected void entryRemoved(boolean evicted, String key,
                                            BitmapDrawable oldValue, BitmapDrawable newValue) {
                    // The removed entry is a standard BitmapDrawable
                    if (VersionUtils.hasHoneycomb()) {
                        // We're running on Honeycomb or later, so add the bitmap
                        // to a SoftReference set for possible use with inBitmap later
                        mReusableBitmaps.add(new SoftReference(oldValue.getBitmap()));
                    }
                }

                /**
                 * Measure item size in kilobytes rather than units which is more practical
                 * for a bitmap cache
                 */
                @Override
                protected int sizeOf(String key, BitmapDrawable value) {
                    final int bitmapSize = getBitmapSize(value) / 1024;
                    return bitmapSize == 0 ? 1 : bitmapSize;
                }
            };
        }

        //Initial disk cache
        if(mCacheParams.diskCacheEnabled) {
            initDiskCache();
        }
    }

    public ImageCacheParams getImageCacheParams() {
        return mCacheParams;
    }

    /**
     * Adds a bitmap to both memory and disk cache.
     * @param data Unique identifier for the bitmap to store
     * @param value The bitmap drawable to store
     */
    public void addBitmapToCache(String data, BitmapDrawable value, boolean diskCacheEnabled) {
        if (data == null || value == null) {
            return;
        }

        // Add to memory cache
        if (mMemoryCache != null) {
            mMemoryCache.put(data, value);
        }

        synchronized (mDiskCacheLock) {
            // Add to disk cache
            if (diskCacheEnabled
                    && mDiskLruCache != null) {
                final String key = hashKeyForDisk(data);
                OutputStream out = null;

                try {
                    DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
                    if (snapshot == null) {
                        final DiskLruCache.Editor editor = mDiskLruCache.edit(key);
                        if (editor != null) {
                            out = editor.newOutputStream(DISK_CACHE_INDEX);
                            value.getBitmap().compress(
                                    mCacheParams.compressFormat, mCacheParams.compressQuality, out);
                            editor.commit();
                            out.close();
                        }
                    } else {
                        snapshot.getInputStream(DISK_CACHE_INDEX).close();
                    }
                } catch (final IOException e) {
                    DebugLog.e(TAG, "addBitmapToCache - " + e);
                } catch (Exception e) {
                    DebugLog.e(TAG, "addBitmapToCache - " + e);
                } finally {
                    try {
                        if (out != null) {
                            out.close();
                        }
                    } catch (IOException e) {}
                }
            }
        }
    }

    /**
     * Get from memory cache.
     *
     * @param data Unique identifier for which item to get
     * @return The bitmap drawable if found in cache, null otherwise
     */
    public BitmapDrawable getBitmapFromMemCache(String data) {
        BitmapDrawable memValue = null;
        if (mMemoryCache != null) {
            memValue = mMemoryCache.get(data);
        }
        return memValue;
    }

    public DiskLruCache getDiskLruCache() {
        synchronized (mDiskCacheLock) {
            return mDiskLruCache;
        }
    }

    /**
     * Get from disk cache.
     *
     * @param data Unique identifier for which item to get
     * @return The bitmap if found in cache, null otherwise
     */
    public Bitmap getBitmapFromDiskCache(String data, int[] measure) {
        final String key = hashKeyForDisk(data);
        Bitmap bitmap = null;

        synchronized (mDiskCacheLock) {
            while (mDiskCacheStarting) {
                try {
                    mDiskCacheLock.wait();
                } catch (InterruptedException e) {}
            }
            if (mDiskLruCache != null) {
                InputStream inputStream = null;
                try {
                    final DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
                    if (snapshot != null) {
                        inputStream = snapshot.getInputStream(DISK_CACHE_INDEX);
                        if (inputStream != null) {
                            FileDescriptor fd = ((FileInputStream) inputStream).getFD();

                            // Decode bitmap, but we don't want to sample so give
                            // MAX_VALUE as the target dimensions
                            bitmap = ImageDecoder.decodeSampledBitmapFromDescriptor(
                                    fd, measure[0], measure[1], this);
                        }
                    }
                } catch (final IOException e) {
                    DebugLog.e(TAG, "GetBitmapFromDiskCache - " + e);
                } finally {
                    try {
                        if (inputStream != null) {
                            inputStream.close();
                        }
                    } catch (IOException e) {}
                }
            }

            return bitmap;
        }
    }

    /**
     * @param options - BitmapFactory.Options with out* options populated
     * @return Bitmap that case be used for inBitmap
     */
    public Bitmap getBitmapFromReusableSet(BitmapFactory.Options options) {
        Bitmap bitmap = null;

        if (mReusableBitmaps != null && !mReusableBitmaps.isEmpty()) {
            synchronized (mReusableBitmaps) {
                final Iterator<SoftReference<Bitmap>> iterator = mReusableBitmaps.iterator();
                Bitmap item;

                while (iterator.hasNext()) {
                    item = iterator.next().get();

                    if (null != item && item.isMutable()) {
                        // Check to see it the item can be used for inBitmap
                        if (canUseForInBitmap(item, options)) {
                            bitmap = item;

                            // Remove from reusable set so it can't be used again
                            iterator.remove();
                            break;
                        }
                    } else {
                        // Remove from the set if the reference has been cleared.
                        iterator.remove();
                    }
                }
            }
        }

        return bitmap;
    }


    /**
     * A holder class that contains cache parameters.
     */
    public static class ImageCacheParams {
        private int memCacheSize = DEFAULT_MEM_CACHE_SIZE;
        private int diskCacheSize = DEFAULT_DISK_CACHE_SIZE;
        private File diskCacheDir;
        private CompressFormat compressFormat = DEFAULT_COMPRESS_FORMAT;
        private int compressQuality = DEFAULT_COMPRESS_QUALITY;
        private boolean memoryCacheEnabled = DEFAULT_MEM_CACHE_ENABLED;
        private boolean diskCacheEnabled = DEFAULT_DISK_CACHE_ENABLED;

        /**
         * Create a set of image cache parameters that can be provided to
         * {@link ImageCache#getInstance(ImageCacheParams)}
         * @param context A context to use.
         * @param diskCacheDirectoryName A unique subdirectory name that will be appended to the
         *                               application cache directory. Usually "cache" or "images"
         *                               is sufficient.
         */
        public ImageCacheParams(Context context, String diskCacheDirectoryName) {
            diskCacheDir = getDiskCacheDir(context, diskCacheDirectoryName);
        }

        /**
         * Setting whether using disk cache
         * @param enabled
         */
        public void setDiskCacheEnabled(boolean enabled) {
            this.diskCacheEnabled = enabled;
        }

        /**
         * Return whether using disk cache
         */
        public boolean getDiskCacheEnabled() {
            return this.diskCacheEnabled;
        }

        /**
         * Sets the memory cache size based on a percentage of the max available VM memory.
         * Eg. setting percent to 0.2 would set the memory cache to one fifth of the available
         * memory. Throws {@link IllegalArgumentException} if percent is < 0.01 or > .8.
         * memCacheSize is stored in kilobytes instead of bytes as this will eventually be passed
         * to construct a LruCache which takes an int in its constructor.
         *
         * This value should be chosen carefully based on a number of factors
         * Refer to the corresponding Android Training class for more discussion:
         * http://developer.android.com/training/displaying-bitmaps/
         *
         * @param percent Percent of available app memory to use to size memory cache
         */
        private void setMemCacheSizePercent(float percent) {
            if (percent < 0.01f || percent > 0.8f) {
                throw new IllegalArgumentException("setMemCacheSizePercent - percent must be "
                        + "between 0.01 and 0.8 (inclusive)");
            }
            memCacheSize = Math.round(percent * Runtime.getRuntime().maxMemory() / 1024);
            DebugLog.e(TAG, "Mem cache size:" + memCacheSize);
        }
    }

    /**
     * @param candidate - Bitmap to check
     * @param targetOptions - Options that have the out* value populated
     * @return true if <code>candidate</code> can be used for inBitmap re-use with
     *      <code>targetOptions</code>
     */
    @TargetApi(VERSION_CODES.KITKAT)
    private static boolean canUseForInBitmap(
            Bitmap candidate, BitmapFactory.Options targetOptions) {
        if (!VersionUtils.hasKitKat()) {
            // On earlier versions, the dimensions must match exactly and the inSampleSize must be 1
            return candidate.getWidth() == targetOptions.outWidth
                    && candidate.getHeight() == targetOptions.outHeight
                    && targetOptions.inSampleSize == 1;
        }

        // From Android 4.4 (KitKat) onward we can re-use if the byte size of the new bitmap
        // is smaller than the reusable bitmap candidate allocation byte count.
        int width = targetOptions.outWidth / targetOptions.inSampleSize;
        int height = targetOptions.outHeight / targetOptions.inSampleSize;
        int byteCount = width * height * getBytesPerPixel(candidate.getConfig());
        return byteCount <= candidate.getAllocationByteCount();
    }

    /**
     * Return the byte usage per pixel of a bitmap based on its configuration.
     * @param config The bitmap configuration.
     * @return The byte usage per pixel.
     */
    private static int getBytesPerPixel(Config config) {
        if (config == Config.ARGB_8888) {
            return 4;
        } else if (config == Config.RGB_565) {
            return 2;
        } else if (config == Config.ARGB_4444) {
            return 2;
        } else if (config == Config.ALPHA_8) {
            return 1;
        }
        return 1;
    }

    /**
     * Get a usable cache directory (external if available, internal otherwise).
     *
     * @param context The context to use
     * @param uniqueName A unique directory name to append to the cache dir
     * @return The cache dir
     */
    public static File getDiskCacheDir(Context context, String uniqueName) {
        // Check if media is mounted or storage is built-in, if so, try and use external cache dir
        // otherwise use internal cache dir
        final String cachePath =
                Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState()) ||
                        !isExternalStorageRemovable() ? getExternalCacheDir(context).getPath() :
                        context.getCacheDir().getPath();

        DebugLog.d("TAG", String.format(Locale.JAPAN,
                "Cache directory: %s", cachePath + File.separator + uniqueName));
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * A hashing method that changes a string (like a URL) into a hash suitable for using as a
     * disk filename.
     */
    public static String hashKeyForDisk(String key) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(key.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(key.hashCode());
        }
        return cacheKey;
    }

    private static String bytesToHexString(byte[] bytes) {
        // http://stackoverflow.com/questions/332079
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    /**
     * Get the size in bytes of a bitmap in a BitmapDrawable. Note that from Android 4.4 (KitKat)
     * onward this returns the allocated memory size of the bitmap which can be larger than the
     * actual bitmap data byte count (in the case it was re-used).
     *
     * @param value
     * @return size in bytes
     */
    @TargetApi(VERSION_CODES.KITKAT)
    public static int getBitmapSize(BitmapDrawable value) {
        Bitmap bitmap = value.getBitmap();

        // From KitKat onward use getAllocationByteCount() as allocated bytes can potentially be
        // larger than bitmap byte count.
        if (VersionUtils.hasKitKat()) {
            return bitmap.getAllocationByteCount();
        }

        if (VersionUtils.hasHoneycombMR1()) {
            return bitmap.getByteCount();
        }

        // Pre HC-MR1
        return bitmap.getRowBytes() * bitmap.getHeight();
    }

    /**
     * Check if external storage is built-in or removable.
     *
     * @return True if external storage is removable (like an SD card), false
     *         otherwise.
     */
    @TargetApi(VERSION_CODES.GINGERBREAD)
    public static boolean isExternalStorageRemovable() {
        if (VersionUtils.hasGingerbread()) {
            return Environment.isExternalStorageRemovable();
        }
        return true;
    }

    /**
     * Get the external app cache directory.
     *
     * @param context The context to use
     * @return The external cache dir
     */
    @TargetApi(VERSION_CODES.FROYO)
    public static File getExternalCacheDir(Context context) {
        if (VersionUtils.hasFroyo()) {
            return context.getExternalCacheDir();
        }

        // Before Froyo we need to construct the external cache dir ourselves
        final String cacheDir = "/Android/data/" + context.getPackageName() + "/cache/";
        return new File(Environment.getExternalStorageDirectory().getPath() + cacheDir);
    }

    /**
     * Check how much usable space is available at a given path.
     *
     * @param path The path to check
     * @return The space available in bytes
     */
    @TargetApi(VERSION_CODES.GINGERBREAD)
    public static long getUsableSpace(File path) {
        if (VersionUtils.hasGingerbread()) {
            return path.getUsableSpace();
        }
        final StatFs stats = new StatFs(path.getPath());
        return (long) stats.getBlockSize() * (long) stats.getAvailableBlocks();
    }

    public void initDiskCache() {
        new CacheAsyncTask().execute(CacheAsyncTask.MESSAGE_INIT_DISK_CACHE);
    }

    public void clearCache() {
        new CacheAsyncTask().execute(CacheAsyncTask.MESSAGE_CLEAR);
    }

    public void flushCache() {
        new CacheAsyncTask().execute(CacheAsyncTask.MESSAGE_FLUSH);
    }

    public void closeCache() {
        new CacheAsyncTask().execute(CacheAsyncTask.MESSAGE_CLOSE);
    }

    /**
     * キャッシュにかんする重いタクスを管理するクラス
     */
    private class CacheAsyncTask extends AsyncTask<Object, Void, Void> {

        public static final int MESSAGE_INIT_DISK_CACHE = 0;
        public static final int MESSAGE_CLEAR = 1;
        public static final int MESSAGE_FLUSH = 2;
        public static final int MESSAGE_CLOSE = 3;

        @Override
        protected Void doInBackground(Object... params) {
            int taskId = (Integer) params[0];

            switch (taskId) {
                case MESSAGE_INIT_DISK_CACHE:
                    initDiskCache();
                    break;

                case MESSAGE_CLEAR:
                    clearCache();
                    break;

                case MESSAGE_FLUSH:
                    flush();
                    break;

                case MESSAGE_CLOSE:
                    close();
                    break;
            }

            return null;
        }

        /**
         * Initializes the disk cache.  Note that this includes disk access so this should not be
         * executed on the main/UI thread. By default an ImageCache does not initialize the disk
         * cache when it is created, instead you should call initDiskCache() to initialize it on a
         * background thread.
         */
        private void initDiskCache() {
            // Set up disk cache
            synchronized (mDiskCacheLock) {

                if (mDiskLruCache == null || mDiskLruCache.isClosed()) {
                    File diskCacheDir = mCacheParams.diskCacheDir;
                    if (mCacheParams.diskCacheEnabled && diskCacheDir != null) {
                        if (!diskCacheDir.exists()) {
                            diskCacheDir.mkdirs();
                        }
                        if (getUsableSpace(diskCacheDir) > mCacheParams.diskCacheSize) {
                            try {
                                mDiskLruCache = DiskLruCache.open(
                                        diskCacheDir, 1, 1, mCacheParams.diskCacheSize);
                                DebugLog.d(TAG, "Finish initialization lru disk cache !");

                            } catch (final IOException e) {
                                mCacheParams.diskCacheDir = null;
                            }
                        }
                    }
                }
                mDiskCacheStarting = false;
                mDiskCacheLock.notifyAll();
            }
        }

        /**
         * Clears both the memory and disk cache associated with this ImageCache object. Note that
         * this includes disk access so this should not be executed on the main/UI thread.
         */
        private void clearCache() {
            if (mMemoryCache != null) {
                mMemoryCache.evictAll();
            }

            mDiskCacheStarting = true;
            if (mDiskLruCache != null && !mDiskLruCache.isClosed()) {
                try {
                    mDiskLruCache.delete();
                    DebugLog.d(TAG, "Disk cache clear !");
                } catch (IOException e) {
                    Log.e(TAG, "clearCache - " + e);
                }
                mDiskLruCache = null;
                initDiskCache();
            }
        }

        /**
         * Flushes the disk cache associated with this ImageCache object. Note that this includes
         * disk access so this should not be executed on the main/UI thread.
         */
        private void flush() {
            synchronized (mDiskCacheLock) {
                if (mDiskLruCache != null) {
                    try {
                        mDiskLruCache.flush();
                        DebugLog.d(TAG, "Disk cache flushed");
                    } catch (IOException e) {
                        DebugLog.e(TAG, "Flush - " + e);
                    }
                }
            }
        }

        /**
         * Closes the disk cache associated with this ImageCache object. Note that this includes
         * disk access so this should not be executed on the main/UI thread.
         */
        private void close() {
            synchronized (mDiskCacheLock) {

                if (mDiskLruCache != null) {
                    try {
                        if (!mDiskLruCache.isClosed()) {
                            mDiskLruCache.close();
                            mDiskLruCache = null;
                            DebugLog.d(TAG, "Disk cache closed");
                        }
                    } catch (IOException e) {
                        DebugLog.e(TAG, "Close - " + e);
                    }
                }
            }
        }
    }
}