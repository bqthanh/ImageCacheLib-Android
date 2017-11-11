package vn.hbs;

import android.app.Application;
import vn.hbs.lib.cache.ImageCache;
import vn.hbs.lib.http.ImageFetcher;

/**
 * Created by thanhbui on 2017/10/04.
 */

public class App extends Application {
    public static final String IMAGE_CACHE_DIR = "images";
    private ImageFetcher mImageFetcher;

    @Override
    public void onCreate() {
        super.onCreate();
        //Init ImageFetcher
        ImageCache.ImageCacheParams cacheParams = new ImageCache.ImageCacheParams(this, IMAGE_CACHE_DIR);
        cacheParams.setDiskCacheEnabled(true);
        mImageFetcher = new ImageFetcher(this, null);
        mImageFetcher.setLoadingImage(R.drawable.empty_photo);
        //You should clear cache if not necessary
        mImageFetcher.clearCache();
    }

    public ImageFetcher getImageFetcher() {
        return mImageFetcher;
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        mImageFetcher.closeCache();
    }
}