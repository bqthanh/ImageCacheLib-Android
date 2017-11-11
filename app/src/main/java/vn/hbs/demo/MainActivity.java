package vn.hbs.demo;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.List;

import vn.hbs.App;
import vn.hbs.R;
import vn.hbs.lib.debug.DebugLog;
import vn.hbs.lib.http.ImageFetcher;
import vn.hbs.lib.http.ImageFetcherListener;
import vn.hbs.lib.util.NetworkUtils;

/**
 * Created by thanhbui on 2017/04/08.
 */
public class MainActivity extends AppCompatActivity implements ImageFetcherListener {
    private static final String TAG = MainActivity.class.getSimpleName();

    private static final int GRID_COLUMN_NUM = 2;

    private RecyclerView mRecyclerView;
    private ImageFetcher mImageFetcher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mRecyclerView = (RecyclerView) findViewById(R.id.recycle_view);

        if (!NetworkUtils.checkConnection(this)) {
            Toast.makeText(this, R.string.no_network_connection, Toast.LENGTH_LONG).show();
        }
        mImageFetcher = ((App) getApplication()).getImageFetcher();
        mImageFetcher.setListener(this);

        setupViews();
    }

    private void setupViews() {
        mRecyclerView.setLayoutManager(new GridLayoutManager(this, GRID_COLUMN_NUM));
        MyAdapter adapter = new MyAdapter(this, ImageProvider.getImageUrlList());
        mRecyclerView.setAdapter(adapter);
    }

    @Override
    public void onImageLoaded(String url, boolean success, int cacheState) {
        DebugLog.i(TAG, String.format("Image loaded %s with status %s and cache state %s", "", success, cacheState));
    }

    @Override
    protected void onResume() {
        super.onResume();
        mImageFetcher.setExitTasksEarly(false);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mImageFetcher.setExitTasksEarly(true);
    }

    private class MyAdapter extends RecyclerView.Adapter<MyAdapter.MyViewHolder> {
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
            //Request an image by specified url into an image view
            boolean diskCacheEnabled = false;
            if (position >= 0 && position < 20) {
                diskCacheEnabled = true;
            }
            mImageFetcher.load(mUrlList.get(position), holder.mImageView, diskCacheEnabled);
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

            public MyViewHolder(View itemView) {
                super(itemView);
                mImageView = (ImageView) itemView.findViewById(R.id.thumbnail);
            }
        }
    }
}