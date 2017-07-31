package vn.hbs.http;

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
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Map;

import vn.hbs.debug.DebugLog;
import vn.hbs.http.cache.DiskLruCache;
import vn.hbs.http.cache.ImageCache;
import vn.hbs.http.util.ImageDecoder;

/**
 * Created by thanhbui on 2017/04/17.
 */
public class HttpRequest {
    private static final String TAG = HttpRequest.class.getSimpleName();

    private static final int FADE_IN_TIME = 200;
    private static final int IO_BUFFER_SIZE = 8 * 1024;
    private static final int DISK_CACHE_INDEX = 0;

    private boolean mFadeInBitmap = true;
    private boolean mExitTasksEarly = false;
    protected boolean mPauseWork = false;

    private Context mContext;
    protected ImageCache mImageCache;
    private Bitmap mLoadingBitmap;
    private HttpRequestListener mListener;

    private MyAsyncTask mTask;
    private final Object mPauseWorkLock = new Object();

    public HttpRequest(Context context) {
        this.mContext = context;
        this.mImageCache = ImageCache.getInstance(mContext);
    }

    /**
     * Set listener for http request
     */
    public void setListener(HttpRequestListener listener) {
        this.mListener = listener;
    }

    /**
     * Set placeholder bitmap that shows when the the background thread is running.
     *
     * @param resId
     */
    public void setLoadingImage(int resId) {
        mLoadingBitmap = BitmapFactory.decodeResource(mContext.getResources(), resId);
    }

    /**
     * Request data specified by the url parameter
     */
    public void request(String method, String urlString, Map<String, String> params) {
        mTask = new MyAsyncTask(method, urlString, params, null);
        mTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /**
     * Request an image specified by the url parameter into an ImageView
     */
    public void request(String urlString, ImageView imageView) {
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
                mListener.onRequestFinish(urlString, value, HttpURLConnection.HTTP_OK);
            }
        } else if (cancelPotentialWork(urlString, imageView)) {
            mTask = new MyAsyncTask(HttpHeader.METHOD_GET, urlString, null, imageView);

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
     * @param imageView Any imageView
     * @return Retrieve the currently active work task (if any) associated with this imageView.
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
        private String mMethod;
        private String mUrl;
        private Map<String, String> mParams;
        private final WeakReference<ImageView> imageViewReference;

        private int mStatus;

        public MyAsyncTask(String method, String url, Map<String, String> params, ImageView imageView) {
            this.mMethod = method;
            this.mUrl = url;
            this.mParams = params;
            this.imageViewReference = new WeakReference(imageView);
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            if (HttpHeader.METHOD_GET.equals(mMethod)
                    && mParams != null) {
                mUrl = String.format("%s?%s", mUrl, mapToQuery(this.mParams));
            }
        }

        /**
         * Background processing.
         */
        @Override
        protected Object doInBackground(Void... params) {
            Bitmap bitmap;
            BitmapDrawable drawable;

            // Wait here if work is paused and the task is not cancelled
            synchronized (mPauseWorkLock) {
                while (mPauseWork && !isCancelled()) {
                    try {
                        mPauseWorkLock.wait();
                    } catch (InterruptedException e) {}
                }
            }

            // If the image cache is available and this task has not been cancelled by another
            // thread and the ImageView that was originally bound to this task is still bound back
            // to this task and our "exit early" flag is not set then try and fetch the bitmap from
            // the cache
            if (mImageCache != null
                    && !isCancelled()
                    && getAttachedImageView() != null
                    && !mExitTasksEarly) {
                bitmap = mImageCache.getBitmapFromDiskCache(mUrl);
                if (bitmap != null) {
                    return convertToBitmapDrawable(bitmap);
                }
            }

            HttpURLConnection urlConnection = null;
            try {
                URL url = new URL(mUrl);
                urlConnection = (HttpURLConnection) url.openConnection();
                if (!mMethod.equals(HttpHeader.METHOD_GET)) {
                    urlConnection.setDoOutput(true);
                    if (mParams != null) {
                        OutputStreamWriter writer = new OutputStreamWriter(urlConnection.getOutputStream());
                        writer.write(mapToQuery(mParams));
                        writer.close();
                    }
                }

                String contentType;
                InputStream inputStream;

                urlConnection.connect();

                mStatus = urlConnection.getResponseCode();
                contentType = urlConnection.getHeaderField(HttpHeader.CONTENT_TYPE);

                if (contentType.contains(HttpHeader.CONTENT_TYPE_IMAGE)) {
                    bitmap = processBitmap(mUrl, urlConnection);
                    drawable = convertToBitmapDrawable(bitmap);
                    return drawable;
                } else {
                    inputStream = urlConnection.getInputStream();
                    String encoding = urlConnection.getContentEncoding();
                    if (encoding == null) {
                        encoding = HttpHeader.ENCODING_DEFAULT;
                    }
                    BufferedReader reader =
                            new BufferedReader(new InputStreamReader(inputStream, encoding));
                    StringBuilder stringBuilder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stringBuilder.append(line);
                    }
                    return stringBuilder.toString();
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

            return null;
        }

        /**
         * Once the image is processed, associates it to the imageView
         */
        @Override
        protected void onPostExecute(Object value) {
            // if cancel was called on this task or the "exit early" flag is set then we're done
            if (isCancelled() || mExitTasksEarly) {
                value = null;
            }
            final ImageView imageView = getAttachedImageView();
            if (value instanceof BitmapDrawable
                    && imageView != null) {
                setImageDrawable(imageView, (BitmapDrawable) value);
            }
            if (mListener != null) {
                mListener.onRequestFinish(mUrl, value, mStatus);
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
                    mImageCache.addBitmapToCache(mUrl, drawable);
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
         * Subclasses should override this to define any processing or work that must happen to produce
         * the final bitmap. This will be executed in a background thread and be long running. For
         * example, you could resize a large bitmap here, or pull down an image from the network.
         *
         * @param urlString The data to identify which image to process
         * @return The processed bitmap
         */
        private Bitmap processBitmap(String urlString, HttpURLConnection urlConnection) {
            final String key;
            DiskLruCache diskLruCache;
            FileDescriptor fileDescriptor = null;
            FileInputStream fileInputStream = null;
            DiskLruCache.Snapshot snapshot;

            diskLruCache = mImageCache.getDiskLruCache();
            key = ImageCache.hashKeyForDisk(urlString);

            try {
                snapshot = diskLruCache.get(key);
                if (snapshot == null) {
                    DiskLruCache.Editor editor = diskLruCache.edit(key);

                    if (editor != null) {
                        if (downloadUrlToStream(urlConnection,
                                editor.newOutputStream(DISK_CACHE_INDEX))) {
                            editor.commit();
                        } else {
                            editor.abort();
                        }
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
                    } catch (IOException e) {}
                }
            }

            Bitmap bitmap = null;
            if (fileDescriptor != null) {
                int reqWidth = Integer.MAX_VALUE, reqHeight = Integer.MAX_VALUE;

                if (getAttachedImageView() != null) {
                    reqWidth = getAttachedImageView().getMeasuredWidth();
                    reqHeight = getAttachedImageView().getMeasuredHeight();
                }

                bitmap = ImageDecoder.decodeSampledBitmapFromDescriptor(fileDescriptor, reqWidth,
                        reqHeight, mImageCache);
            }
            if (fileInputStream != null) {
                try {
                    fileInputStream.close();
                } catch (IOException e) {}
            }
            return bitmap;
        }

        /**
         * Download a bitmap from a URL and write the content to an output stream.
         */
        private boolean downloadUrlToStream(HttpURLConnection urlConnection, OutputStream outputStream) {
            BufferedOutputStream out = null;
            BufferedInputStream in = null;

            try {
                in = new BufferedInputStream(urlConnection.getInputStream(), IO_BUFFER_SIZE);
                out = new BufferedOutputStream(outputStream, IO_BUFFER_SIZE);

                int b;
                while ((b = in.read()) != -1) {
                    out.write(b);
                }
                return true;
            } catch (final MalformedURLException e) {
                DebugLog.e(TAG, "Url exception: " + e.getLocalizedMessage());
            } catch (IOException e) {
                DebugLog.e(TAG, "IO exception string value: " + e.getLocalizedMessage());
            }
            finally {
                try {
                    if (out != null) {
                        out.close();
                    }
                    if (in != null) {
                        in.close();
                    }
                } catch (final IOException e) {}
            }
            return false;
        }
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
     * Convert the map to an array of string for query string
     */
    private String mapToQuery(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        String separator = "&";
        int count = 0;

        if (map != null) {
            for (Map.Entry<String, String> e : map.entrySet()) {
                if (count > 0) {
                    sb.append(separator);
                }
                try {
                    sb.append(e.getKey() + "=" + URLEncoder.encode(e.getValue(), HttpHeader.ENCODING_DEFAULT));
                } catch (UnsupportedEncodingException ex) {
                    DebugLog.i(TAG, "Exception: " + ex.getLocalizedMessage());
                }
                count++;
            }
        }

        return sb.toString();
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
     * Cancels any pending work attached to the provided ImageView.
     * @param imageView
     */
    public void cancelWork(ImageView imageView) {
        final MyAsyncTask bitmapWorkerTask = getBitmapWorkerTask(imageView);
        if (bitmapWorkerTask != null) {
            bitmapWorkerTask.cancel(true);
            final Object url = bitmapWorkerTask.mUrl;
            DebugLog.i(TAG, "Cancelled work: " + url);
        }
    }

    /**
     * You should implements this on onPause and onResume lifecyfile
     */
    public void setExitTasksEarly(boolean exitTasksEarly) {
        mExitTasksEarly = exitTasksEarly;
        setPauseWork(false);
    }

    public void clearCache() {
        if (mImageCache != null) {
            mImageCache.clearCache();
        }
    }

    public void closeCache() {
        if (mImageCache != null) {
            mImageCache.closeCache();
        }
    }
}