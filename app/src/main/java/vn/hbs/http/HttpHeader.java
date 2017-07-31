package vn.hbs.http;

/**
 * Created by thanhbui on 2017/06/10.
 */

public final class HttpHeader {

    public static final String ENCODING_DEFAULT = "utf-8";

    /* Request method */
    public static final String METHOD_GET = "GET";
    public static final String METHOD_POST = "POST";
    public static final String METHOD_PUT = "PUT";
    public static final String METHOD_PATCH = "PATCH";
    public static final String METHOD_DELETE = "DELETE";

    /* Http content type */
    public static final String CONTENT_TYPE = "content-type";
    public static final String CONTENT_TYPE_TEXT = "text";
    public static final String CONTENT_TYPE_IMAGE = "image";
}