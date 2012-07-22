package com.webimageloader.ext;

import com.webimageloader.ImageLoader;
import com.webimageloader.ImageLoader.Listener;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.util.Log;
import android.widget.ImageView;

public class ImageHelper {
    private static final String TAG = "ImageHelper";

    private Context context;
    private ImageLoader loader;

    private int loadingResource;
    private int errorResource;
    private boolean fadeIn = true;

    private LoadingListener listener;

    public ImageHelper(Context context, ImageLoader loader) {
        this.context = context;
        this.loader = loader;

        listener = new LoadingListener();
    }

    public ImageHelper setLoadingResource(int loadingResource) {
        this.loadingResource = loadingResource;

        return this;
    }

    public ImageHelper setErrorResource(int errorResource) {
        this.errorResource = errorResource;

        return this;
    }

    public ImageHelper setFadeIn(boolean fadeIn) {
        this.fadeIn = fadeIn;

        return this;
    }

    public void load(ImageView v, String url) {
        Bitmap b = loader.load(v, url, listener);

        if (b != null) {
            v.setImageBitmap(b);
        } else if (loadingResource != 0) {
            v.setImageResource(loadingResource);
        } else {
            v.setImageBitmap(null);
        }
    }

    private class LoadingListener implements Listener<ImageView> {
        @Override
        public void onSuccess(ImageView v, Bitmap b) {
            if (!fadeIn) {
                v.setImageBitmap(b);
            } else {
                Drawable old = v.getDrawable();
                if (old == null) {
                    old = new ColorDrawable(android.R.color.transparent);
                }

                TransitionDrawable d = new TransitionDrawable(new Drawable[] {
                        old,
                        new BitmapDrawable(context.getResources(), b) });

                v.setImageDrawable(d);
                d.startTransition(300);
            }
        }

        @Override
        public void onError(ImageView v, Throwable t) {
            Log.d(TAG, "Error loading bitmap", t);
            v.setImageResource(errorResource);
        }
    }
}
