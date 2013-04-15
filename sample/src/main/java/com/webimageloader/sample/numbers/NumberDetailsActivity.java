package com.webimageloader.sample.numbers;

import com.webimageloader.ImageLoader;
import com.webimageloader.ext.ImageHelper;
import com.webimageloader.ext.ImageLoaderApplication;
import com.webimageloader.sample.R;
import com.webimageloader.transformation.SimpleTransformation;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.widget.ImageView;

public class NumberDetailsActivity extends Activity {
    public static final String ARG_URL = "url";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_numbers_details);

        String url = getIntent().getStringExtra(ARG_URL);

        ImageLoader imageLoader = ImageLoaderApplication.getLoader(this);
        ImageView imageView = (ImageView) findViewById(R.id.image);

        FlipTransformation t = new FlipTransformation();
        new ImageHelper(this, imageLoader)
                .setFadeIn(true)
                .load(imageView, url, t);
    }

    private static class FlipTransformation extends SimpleTransformation {
        @Override
        public String getIdentifier() {
            return "flip";
        }

        @Override
        public Bitmap transform(Bitmap b) {
            Matrix m = new Matrix();
            m.preScale(-1, 1);

            return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
        }
    }
}
