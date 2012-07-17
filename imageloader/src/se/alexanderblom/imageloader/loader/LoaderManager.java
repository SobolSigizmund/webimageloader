package se.alexanderblom.imageloader.loader;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import se.alexanderblom.imageloader.Request;
import se.alexanderblom.imageloader.transformation.Transformation;
import se.alexanderblom.imageloader.util.BitmapUtils;
import android.graphics.Bitmap;
import android.util.Log;

public class LoaderManager {
    private DiskLoader diskLoader;
    private NetworkLoader networkLoader;
    private TransformingLoader transformingLoader;

    private List<Loader> standardChain;

    public interface Listener {
        void onLoaded(Bitmap b);
        void onError(Throwable t);
    }

    public LoaderManager(DiskLoader diskLoader, NetworkLoader networkLoader) {
        this.diskLoader = diskLoader;
        this.networkLoader = networkLoader;
        transformingLoader = new TransformingLoader();

        standardChain = new ArrayList<Loader>();
        if (diskLoader != null) {
            standardChain.add(diskLoader);
        }
        standardChain.add(networkLoader);
    }

    public void load(Request request, final Listener listener) {
        List<Loader> chain = standardChain;

        Transformation transformation = request.getTransformation();
        if (transformation != null) {
            // Use special chain with transformation
            ArrayList<Loader> loaderChain = new ArrayList<Loader>();
            loaderChain.add(diskLoader);
            loaderChain.add(transformingLoader);
            loaderChain.add(diskLoader);
            loaderChain.add(networkLoader);

            chain = loaderChain;
        }

        Iterator<Loader> it = chain.iterator();
        it.next().load(request, it, new Loader.Listener() {
            @Override
            public void onStreamLoaded(InputStream is) {
                Bitmap b = BitmapUtils.decodeStream(is);
                onBitmapLoaded(b);
            }

            @Override
            public void onBitmapLoaded(Bitmap b) {
                listener.onLoaded(b);
            }

            @Override
            public void onError(Throwable t) {
                listener.onError(t);
            }
        });
    }

    public void close() {
        if (diskLoader != null) {
            diskLoader.close();
        }
    }

    private static class TransformingLoader implements Loader {
        private static final String TAG = "TransformingLoader";

        @Override
        public void load(Request request, Iterator<Loader> chain, final Listener listener) {
            Log.d(TAG, "Transforming " + request);

            final Transformation transformation = request.getTransformation();

            // Modify request
            Request modified = request.withoutTransformation();
            chain.next().load(modified, chain, new Listener() {
                @Override
                public void onStreamLoaded(InputStream is) {
                    Bitmap b = transformation.transform(is);
                    deliverResult(b);
                }

                @Override
                public void onBitmapLoaded(Bitmap b) {
                    b = transformation.transform(b);
                    deliverResult(b);
                }

                private void deliverResult(Bitmap b) {
                    if (b == null) {
                        onError(new NullPointerException("Transformer returned null"));
                    } else {
                        listener.onBitmapLoaded(b);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    listener.onError(t);
                }
            });
        }
    }
}
