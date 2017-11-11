package vn.hbs.lib.http;

/**
 * Created by thanhbui on 2017/05/10.
 * Interface definition for callback on image loaded successfully.
 */
public interface ImageFetcherListener {

    int CACHE_MISS = -1;
    int MEMORY_CACHE_HIT = 0;
    int DISK_CACHE_HIT = 1;

    /**
     * Called once the image has been loaded.
     * @param success True if the image was loaded successfully, false if
     *                there was an error.
     * @param -1 load image from URL
     *         0 load image from memory cache
     *         1 load image from disk cache
     */
    void onImageLoaded(String url, boolean success, int cacheState);
}