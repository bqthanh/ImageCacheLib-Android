package vn.hbs.http;

/**
 * Created by thanhbui on 2017/05/10.
 */

public interface HttpRequestListener {
    void onRequestFinish(String url, Object response, int status);
}