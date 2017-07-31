package vn.hbs.ui;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import vn.hbs.R;
import vn.hbs.debug.DebugLog;
import vn.hbs.http.HttpHeader;
import vn.hbs.http.HttpRequest;
import vn.hbs.http.HttpRequestListener;
import vn.hbs.http.util.NetworkUtils;
import vn.hbs.provider.ImageProvider;

/**
 * Created by thanhbui on 2017/04/08.
 */
public class MainActivity extends AppCompatActivity implements HttpRequestListener {
    private final String TAG = MainActivity.class.getSimpleName();

    private RecyclerView mRecyclerView;
    private TextView mHeaderView;

    private HttpRequest mHttpRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mRecyclerView = (RecyclerView) findViewById(R.id.recycle_view);
        mHeaderView = (TextView) findViewById(R.id.tv_header);

        if (!NetworkUtils.checkConnection(this)) {
            Toast.makeText(this, R.string.no_network_connection, Toast.LENGTH_LONG).show();
        }

        //Setup http request
        mHttpRequest = new HttpRequest(this);
        mHttpRequest.setListener(this);
        mHttpRequest.setLoadingImage(R.drawable.empty_photo);

        //Clear internal cache
        mHttpRequest.clearCache();

        //Initiate view
        setupViews();

        //Request json data
        requestLogin();
    }

    /**
     * Initial view
     */
    private void setupViews() {
        MyAdapter adapter = new MyAdapter(this, ImageProvider.getImageUrlList());
        mRecyclerView.setAdapter(adapter);
        mRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayout.VERTICAL, false));
    }

    /**
     * Request a json data
     */
    public void requestLogin() {
        String apiUrl = "https://api.myjson.com/bins/1fudv1";

        Map<String, String> params = new HashMap<>();
        params.put("user_id", "user_id");
        params.put("password", "passwd");
        params.put("device_token", "f8d5a455afc78c9e042bbf256d7cb80b");
        params.put("os", "0");

        mHttpRequest.request(HttpHeader.METHOD_GET, apiUrl, null);
    }

    @Override
    public void onRequestFinish(String url, final Object response, int status) {
        if (response instanceof String) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        JSONObject jsonObject = new JSONObject(response.toString());
                        mHeaderView.setText("message: " + jsonObject.getString("message") + "\n" +
                                "user_name: " + jsonObject.getString("user_name") + "\n" +
                                "office_cd: " + jsonObject.getString("office_cd"));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            });
        }
    }

    public class MyAdapter extends RecyclerView.Adapter<MyAdapter.MyViewHolder> {
        private Context mContext;
        private List<String> mUrlList;

        public MyAdapter(Context context, List<String> urlList) {
            this.mContext = context;
            this.mUrlList = urlList;
        }

        @Override
        public MyViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View item = LayoutInflater.from(mContext).inflate(R.layout.item_view, parent, false);
            return new MyViewHolder(item);
        }

        @Override
        public void onBindViewHolder(MyViewHolder holder, int position) {
            //Request an image specified url into an image view
            DebugLog.i(TAG, "Url at position: " + position + " - " + mUrlList.get(position));
            mHttpRequest.request(mUrlList.get(position), holder.mImageView);
            String[] nameArray = mUrlList.get(position).split("/");
            holder.mBody.setText(nameArray[nameArray.length - 1]);
        }

        @Override
        public int getItemCount() {
            if (mUrlList == null) {
                return 0;
            }
            return mUrlList.size();
        }

        public class MyViewHolder extends RecyclerView.ViewHolder {
            private ImageView mImageView;
            private TextView mBody;

            public MyViewHolder(View itemView) {
                super(itemView);
                mImageView = (ImageView) itemView.findViewById(R.id.thumbnail);
                mBody = (TextView) itemView.findViewById(R.id.body);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHttpRequest.closeCache();
    }
}