package vn.hbs.lib.http;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.os.AsyncTask;
import android.support.v4.content.ContextCompat;
import android.text.TextUtils;
import android.widget.ImageView;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import vn.hbs.lib.cache.DiskLruCache;
import vn.hbs.lib.cache.ImageCache;
import vn.hbs.lib.debug.DebugLog;
import vn.hbs.lib.util.ImageDecoder;

/**
 * Created by thanhbui on 2017/04/17.
 */
public class ImageFetcher {
    private static final String TAG = ImageFetcher.class.getSimpleName();

    private static final String CONTENT_TYPE = "content-type";
    private static final String CONTENT_TYPE_IMAGE = "image";

    private static final int FADE_IN_TIME = 200;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;

    private boolean mFadeInBitmap = true;
    private boolean mExitTasksEarly = false;
    protected boolean mPauseWork = false;

    private Context mContext;
    protected ImageCache mImageCache;
    private Bitmap mLoadingBitmap;
    private ImageFetcherListener mListener;

    private final Object mPauseWorkLock = new Object();

    /**
     * Init ImageFetcher and an ImageCache to this to handle disk and memory bitmap caching.
     */
    public ImageFetcher(Context context, ImageCache.ImageCacheParams cacheParams) {
        this.mContext = context;
        mImageCache = ImageCache.getInstance(cacheParams);
    }

    public void setListener(ImageFetcherListener listener) {
        this.mListener = listener;
    }

    /**
     * Set placeholder bitmap that shows when the the background thread is running.
     */
    public void setLoadingImage(int resId) {
        mLoadingBitmap = BitmapFactory.decodeResource(mContext.getResources(), resId);
    }

    /**
     * Request an image specified by the url parameter into an ImageView
     */
    public void load(String urlString, ImageView imageView, boolean diskCacheEnabled) {
        BitmapDrawable value = null;

        if (TextUtils.isEmpty(urlString)) {
            return;
        }
        if (mImageCache != null) {
            value = mImageCache.getBitmapFromMemCache(urlString);
        }

        if (value != null) {
            // Bitmap found in memory cache
            imageView.setImageDrawable(value);

            if (mListener != null) {
                mListener.onImageLoaded(urlString, true, ImageFetcherListener.MEMORY_CACHE_HIT);
            }
        } else if (cancelPotentialWork(urlString, imageView)) {
            if (mImageCache != null
                    && !mImageCache.getImageCacheParams().getDiskCacheEnabled()) {
                mImageCache.getImageCacheParams().setDiskCacheEnabled(true);
                mImageCache.initDiskCache();
            }
            MyAsyncTask mTask =
                    new MyAsyncTask(urlString, imageView, diskCacheEnabled);
            final AsyncDrawable asyncDrawable =
                    new AsyncDrawable(mContext.getResources(), mLoadingBitmap, mTask);
            imageView.setImageDrawable(asyncDrawable);
            mTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        }
    }

    /**
     * Returns true if the current work has been canceled or if there was no work in
     * progress on this image view.
     * Returns false if the work in progress deals with the same data. The work is not
     * stopped in that case.
     */
    public static boolean cancelPotentialWork(String urlString, ImageView imageView) {
        final MyAsyncTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final String preUrl = bitmapWorkerTask.mUrl;
            if (preUrl == null || !preUrl.equals(urlString)) {
                bitmapWorkerTask.cancel(true);
            } else {
                // The same work is already in progress.
                return false;
            }
        }
        return true;
    }

    /**
     * Retrieve the currently active work task (if any) associated with this imageView.
     * null if there is no such task.
     */
    private static MyAsyncTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    /**
     * The actual AsyncTask that will asynchronously process the image.
     */
    private class MyAsyncTask extends AsyncTask<Void, Void, Object> {
        private String mUrl;
        private final WeakReference<ImageView> imageViewReference;
        private boolean mDiskCacheEnabled;
        private int mCacheState = ImageFetcherListener.CACHE_MISS;

        public MyAsyncTask(String url, ImageView imageView, boolean diskCacheEnabled) {
            this.mUrl = url;
            this.imageViewReference = new WeakReference(imageView);
            this.mDiskCacheEnabled = diskCacheEnabled;
        }

        /**
         * Background processing.
         */
        @Override
        protected Object doInBackground(Void... params) {
            Object retObj = null;

            // Wait here if work is paused and the task is not cancelled
            synchronized (mPauseWorkLock) {
                while (mPauseWork && !isCancelled()) {
                    try {
                        mPauseWorkLock.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }

            // If the image cache is available and this task has not been cancelled by another
            // thread and the ImageView that was originally bound to this task is still bound back
            // to this task and our "exit early" flag is not set then try and fetch the bitmap from
            // the cache
            if (mImageCache != null
                    && mDiskCacheEnabled
                    && mImageCache.getDiskLruCache() != null
                    && !isCancelled()
                    && getAttachedImageView() != null
                    && !mExitTasksEarly) {
                int[] measure = getImageViewMeasures(getAttachedImageView());
                Bitmap bitmap = mImageCache.getBitmapFromDiskCache(mUrl, measure);
                if (bitmap != null) {
                    mCacheState = ImageFetcherListener.DISK_CACHE_HIT;
                    return convertToBitmapDrawable(bitmap);
                }
            }

            HttpURLConnection urlConnection = null;
            try {
                URL url = new URL(mUrl);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.connect();

                if (urlConnection.getHeaderField(CONTENT_TYPE).contains(CONTENT_TYPE_IMAGE)) {
                    if (!mExitTasksEarly) {
                        Bitmap bitmap = processBitmap(mUrl, urlConnection);
                        retObj = convertToBitmapDrawable(bitmap);
                    }
                }
            } catch (IOException e) {
                DebugLog.e(TAG, "IO exception: " + e);
            } catch (Exception e) {
                DebugLog.e(TAG, "Exception: " + e);
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }

            return retObj;
        }

        /**
         * Once the image is processed, associates it to the imageView
         */
        @Override
        protected void onPostExecute(Object value) {
            boolean success = false;

            // if cancel was called on this task or the "exit early" flag is set then we're done
            if (isCancelled() || mExitTasksEarly) {
                value = null;
            }

            final ImageView imageView = getAttachedImageView();
            if (value instanceof BitmapDrawable
                    && imageView != null) {
                success = true;
                setImageDrawable(imageView, (BitmapDrawable) value);
            }

            if (mListener != null) {
                mListener.onImageLoaded(mUrl, success, mCacheState);
            }
        }

        @Override
        protected void onCancelled(Object value) {
            super.onCancelled(value);
            synchronized (mPauseWorkLock) {
                mPauseWorkLock.notifyAll();
            }
        }

        /**
         * Convert bitmap to BitmapDrawable
         */
        private BitmapDrawable convertToBitmapDrawable(Bitmap bitmap) {
            BitmapDrawable drawable = null;

            if (bitmap != null) {
                // Running on Honeycomb or newer, so wrap in a standard BitmapDrawable
                drawable = new BitmapDrawable(mContext.getResources(), bitmap);
                if (mImageCache != null) {
                    mImageCache.addBitmapToCache(mUrl, drawable, mDiskCacheEnabled);
                }
            }

            return drawable;
        }

        /**
         * Returns the ImageView associated with this task as long as the ImageView's task still
         * points to this task as well. Returns null otherwise.
         */
        private ImageView getAttachedImageView() {
            final ImageView imageView = imageViewReference.get();
            final MyAsyncTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

            if (this == bitmapWorkerTask) {
                return imageView;
            }

            return null;
        }

        /**
         * Process bitmap in a background thread and be long running
         */
        private Bitmap processBitmap(String urlString, HttpURLConnection urlConnection) {
            Bitmap bitmap;
            DiskLruCache diskLruCache;
            DiskLruCache.Snapshot snapshot;
            FileDescriptor fileDescriptor = null;

            byte[] byteArray = downloadByteArray(urlConnection);

            if (mExitTasksEarly
                    || byteArray == null
                    || byteArray.length == 0) {
                return null;
            }

            if (mImageCache != null
                    && mDiskCacheEnabled
                    && mImageCache.getDiskLruCache() != null) {
                FileInputStream fileInputStream = null;

                diskLruCache = mImageCache.getDiskLruCache();
                String key = ImageCache.hashKeyForDisk(urlString);

                try {
                    snapshot = diskLruCache.get(key);
                    if (snapshot == null) {
                        DiskLruCache.Editor editor = diskLruCache.edit(key);
                        if (editor != null) {
                            OutputStream out = editor.newOutputStream(DISK_CACHE_INDEX);
                            out.write(byteArray);
                            editor.commit();
                            out.close();
                        }
                        snapshot = diskLruCache.get(key);
                    }

                    if (snapshot != null) {
                        fileInputStream =
                                (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
                        fileDescriptor = fileInputStream.getFD();
                    }

                } catch (IOException e) {
                    DebugLog.e(TAG, "Process bitmap: " + e.getLocalizedMessage());
                } catch (IllegalStateException e) {
                    DebugLog.e(TAG, "Process bitmap: " + e.getLocalizedMessage());
                } finally {
                    if (fileDescriptor == null && fileInputStream != null) {
                        try {
                            fileInputStream.close();
                        } catch (IOException e) {
                        }
                    }
                }
            }

            int[] measure = getImageViewMeasures(getAttachedImageView());
            if (fileDescriptor != null) {
                bitmap = ImageDecoder.decodeSampledBitmapFromDescriptor(fileDescriptor, measure[0], measure[1], mImageCache);
            } else {
                bitmap = ImageDecoder.decodeSampledBitmapFromByteArray(byteArray, measure[0], measure[1], mImageCache);
            }
            return bitmap;
        }

        private int[] getImageViewMeasures(ImageView imageView) {
            if (imageView == null) {
                return new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE};
            }
            int[] measure = new int[] {imageView.getMeasuredWidth(), imageView.getMeasuredHeight()};
            if (measure[0] <= 0
                    || measure[1] <= 0) {
                measure = new int[] {Integer.MAX_VALUE, Integer.MAX_VALUE};
            }
            return measure;
        }
    }

    /**
     * Download a bitmap from a URL in byte array
     */
    private byte[] downloadByteArray(HttpURLConnection urlConnection) {
        BufferedInputStream inputStream = null;
        ByteArrayOutputStream outputStream = null;
        byte[] byteArray = null;

        try {
            inputStream = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
            outputStream = new ByteArrayOutputStream(IO_BUFFER_SIZE);

            int b;
            while ((b = inputStream.read()) != -1) {
                outputStream.write(b);
            }
            byteArray = outputStream.toByteArray();

        } catch (final MalformedURLException e) {
            DebugLog.e(TAG, "Url exception: " + e.getLocalizedMessage());
        } catch (IOException e) {
            DebugLog.e(TAG, "IO exception string value: " + e.getLocalizedMessage());
        }
        finally {
            try {
                if (outputStream != null) {
                    outputStream.close();
                }
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (final IOException e) {}
        }

        return byteArray;
    }

    /**
     * A custom Drawable that will be attached to the imageView while the work is in progress.
     * Contains a reference to the actual worker task, so that it can be stopped if a new binding is
     * required, and makes sure that only the last started worker process can bind its result,
     * independently of the finish order.
     */
    private static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<MyAsyncTask> bitmapWorkerTaskReference;

        public AsyncDrawable(Resources res, Bitmap bitmap, MyAsyncTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference =
                    new WeakReference(bitmapWorkerTask);
        }

        public MyAsyncTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    /**
     * Called when the processing is complete and the final drawable should be
     * set on the ImageView.
     *
     * @param imageView
     * @param drawable
     */
    private void setImageDrawable(ImageView imageView, Drawable drawable) {
        if (mFadeInBitmap) {
            // Transition drawable with a transparent drawable and the final drawable
            final TransitionDrawable td =
                    new TransitionDrawable(new Drawable[] {
                            new ColorDrawable(ContextCompat.getColor(mContext, android.R.color.transparent)),
                            drawable
                    });
            // Set background to loading bitmap
            imageView.setBackground(
                    new BitmapDrawable(mContext.getResources(), mLoadingBitmap));

            imageView.setImageDrawable(td);
            td.startTransition(FADE_IN_TIME);
        } else {
            imageView.setImageDrawable(drawable);
        }
    }

    /**
     * Pause any ongoing background work. This can be used as a temporary
     * measure to improve performance. For example background work could
     * be paused when a ListView or GridView is being scrolled using a
     * {@link android.widget.AbsListView.OnScrollListener} to keep
     * scrolling smooth.
     * <p>
     * If work is paused, be sure setPauseWork(false) is called again
     * before your fragment or activity is destroyed (for example during
     * {@link android.app.Activity#onPause()}), or there is a risk the
     * background thread will never finish.
     */
    public void setPauseWork(boolean pauseWork) {
        synchronized (mPauseWorkLock) {
            mPauseWork = pauseWork;
            if (!mPauseWork) {
                mPauseWorkLock.notifyAll();
            }
        }
    }

    /**
     * Cancels any pending work attached to the provided ImageView
     */
    public boolean cancelWork(ImageView imageView) {
        final MyAsyncTask bitmapWorkerTask = getBitmapWorkerTask(imageView);
        if (bitmapWorkerTask != null) {
            bitmapWorkerTask.cancel(true);
            return true;
        }
        return false;
    }

    /**
     * You should implements this on onPause and onResume lifecycle
     */
    public void setExitTasksEarly(boolean exitTasksEarly) {
        mExitTasksEarly = exitTasksEarly;
        setPauseWork(false);
    }

    /**
     * Clear all data in memory cache and disk cache
     */
    public void clearCache() {
        if (mImageCache != null) {
            mImageCache.clearCache();
        }
    }

    /**
     * You should close disk cache when not use
     */
    public void closeCache() {
        if (mImageCache != null) {
            mImageCache.closeCache();
        }
    }
}