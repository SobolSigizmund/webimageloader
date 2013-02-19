package com.webimageloader.ext;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.AbsListView;
import android.widget.Adapter;
import android.widget.GridView;
import android.widget.ListView;
import com.webimageloader.ImageLoader;
import com.webimageloader.Request;
import com.webimageloader.loader.MemoryCache;
import com.webimageloader.util.AbstractImageLoader;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Specialized ImageLoader for use with a {@link ListView} or {@link GridView}.
 * It will pause updates when the list is flinged and handle preloads.
 * <p>
 * This class does not support the {@link #loadBlocking(com.webimageloader.Request)} methods.
 */
public class ListImageLoader extends AbstractImageLoader {
    private static final String TAG = "ListImageLoader";

    public static final int DEFAULT_PRELOAD_COUNT = 4;

    private static final int MESSAGE_DISPATCH_REQUESTS = 1;
    private static final int MESSAGE_PRELOAD = 2;
    private static final int DISPATCH_DELAY = 550;
    private static final int PRELOAD_DELAY = 300;

    /**
     * Used for preloading a list item.
     *
     * @see #setPreloader(Preloader)
     */
    public interface Preloader {
        /**
         * Called when a specific position should be preloaded. You should call
         * {@link #preload(com.webimageloader.Request)} for the current position.
         *
         * @param position the position
         */
        void preload(int position);
    }

    private AbsListView listView;
    private int numColumns;
    private int preloadCount = DEFAULT_PRELOAD_COUNT;
    private Preloader preloader;
    private boolean interceptLoads = false;

    private ImageLoader imageLoader;
    private Map<Object, RequestEntry<?>> requests;

    private boolean fingerUp = true;
    private int lastScrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE;
    private Handler handler;

    private int lastFirstVisibleItem;
    private PreloaderHandler preloaderHandler;

    /**
     * Create a new {@link ListImageLoader} using the root {@link ImageLoader}.
     *
     * @param imageLoader the root {@link ImageLoader}
     * @param listView the list view
     */
    public ListImageLoader(ImageLoader imageLoader, final AbsListView listView) {
        this.listView = listView;
        this.imageLoader = imageLoader;

        // We don't use weak keys here because this loader should have the same lifetime
        // as the tags themselves
        // accessOrder = true as we want tags in last put order
        requests = new LinkedHashMap<Object, RequestEntry<?>>(16, 0.75f, true);
        preloaderHandler = new PreloaderHandler();

        listView.setOnScrollListener(new ScrollManager());
        listView.setOnTouchListener(new FingerTracker());

        handler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case MESSAGE_DISPATCH_REQUESTS:
                        dispatchRequests();
                        break;
                    case MESSAGE_PRELOAD:
                        preloaderHandler.preload((Direction) msg.obj, msg.arg1, msg.arg2);
                        break;
                    default:
                        return false;
                }

                return true;
            }
        });

        numColumns = 1;

        // If this is a GridView it can have more than 1 column, we'll have to determine the number
        // after a layout pass
        if (listView instanceof GridView) {
            final GridView gridView = (GridView) listView;
            gridView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    // We don't remove the observer as the column count may change later
                    int newNumColumns = gridView.getNumColumns();
                    if (newNumColumns != numColumns) {
                        numColumns = newNumColumns;
                        if (Logger.VERBOSE) Log.d(TAG, "GridView column count: " + numColumns);
                    }
                }
            });
        }
    }

    @Override
    public MemoryCache.DebugInfo getMemoryCacheInfo() {
        return imageLoader.getMemoryCacheInfo();
    }

    @Override
    public MemoryCache getMemoryCache() {
        return imageLoader.getMemoryCache();
    }

    @Override
    public Bitmap loadBlocking(Request request) throws IOException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void preload(Request request) {
        imageLoader.preload(request);
    }

    @Override
    public <T> Bitmap load(T tag, Request request, Listener<T> listener) {
        if (interceptLoads) {
            preload(request);
            return null;
        }

        if (lastScrollState == AbsListView.OnScrollListener.SCROLL_STATE_FLING) {
            Bitmap b = imageLoader.get(request);
            if (b != null) {
                // Bitmap in memory cache, cancel possible other requests for this tag
                requests.remove(tag);
                imageLoader.cancel(tag);
            } else {
                // Not in cache, cancel previous fetches and queue this request
                imageLoader.cancel(tag);
                requests.put(tag, new RequestEntry<T>(tag, request, listener));
            }

            return b;
        } else {
            // It's possible we have a pending request for this
            requests.remove(tag);

            return imageLoader.load(tag, request, listener);
        }
    }

    @Override
    public Bitmap get(Request request) {
        return imageLoader.get(request);
    }

    @Override
    public <T> void cancel(T tag) {
        imageLoader.cancel(tag);
    }

    @Override
    public void destroy() {
        imageLoader.destroy();
    }

    /**
     * Set the {@link Adapter} to be used for preloading. This is easier than having
     * a separate {@link Preloader} but should only be used for very simple adapters. This
     * will call {@link Adapter#getView(int, android.view.View, android.view.ViewGroup)}
     * for every preload and intercept the load calls transforming them to preloads.
     *
     * @param adapter the adapter to be used for preloading
     */
    public void setPreloadAdapter(Adapter adapter) {
        preloader = new AdapterPreloader(adapter);
    }

    /**
     * Set a {@link Preloader} to be used for preloading.
     *
     * @param preloader the preloader
     */
    public void setPreloader(Preloader preloader) {
        this.preloader = preloader;
    }

    /**
     * Set the number of items to be preloaded. Note that this will be multiplied by
     * the number of columns for a {@link GridView}.
     * @param preloadCount number of items to preload
     */
    public void setPreloadCount(int preloadCount) {
        this.preloadCount = preloadCount;
    }

    private void dispatchRequests() {
        if (requests.isEmpty()) {
            return;
        }

        for (RequestEntry<?> entry : requests.values()) {
            entry.load(imageLoader);
        }

        if (Logger.VERBOSE) Log.d(TAG, "Dispatched " + requests.size() + " requests");

        requests.clear();
    }

    private static class RequestEntry<T> {
        private final T tag;
        private final Request request;
        private final Listener<T> listener;

        private RequestEntry(T tag, Request request, Listener<T> listener) {
            this.tag = tag;
            this.request = request;
            this.listener = listener;
        }

        public void load(ImageLoader imageLoader) {
            Bitmap b = imageLoader.load(tag, request, listener);
            if (b != null) {
                listener.onSuccess(tag, b);
            }
        }
    }

    private class ScrollManager implements AbsListView.OnScrollListener {
        @Override
        public void onScrollStateChanged(AbsListView view, int scrollState) {
            boolean stoppedFling = lastScrollState == SCROLL_STATE_FLING &&
                    scrollState != SCROLL_STATE_FLING;

            if (stoppedFling) {
                preload(view.getFirstVisiblePosition(), view.getLastVisiblePosition(), view.getCount(), PRELOAD_DELAY);

                handler.removeMessages(MESSAGE_DISPATCH_REQUESTS);

                // Delay loading if the finger is down as this may mean they are just
                // continuing their fling
                int delay = fingerUp ? 0 : DISPATCH_DELAY;
                handler.sendEmptyMessageDelayed(MESSAGE_DISPATCH_REQUESTS, delay);
            } else if (scrollState == SCROLL_STATE_FLING) {
                handler.removeMessages(MESSAGE_DISPATCH_REQUESTS);
                handler.removeMessages(MESSAGE_PRELOAD);
            }

            lastScrollState = scrollState;
        }

        @Override
        public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
            if (lastScrollState != SCROLL_STATE_FLING) {
                // When not using a delay here we might unnecessarily preload a few extra images,
                // it's hard to get around this
                int lastVisible = firstVisibleItem + visibleItemCount - 1;
                preload(firstVisibleItem, lastVisible, totalItemCount, 0);
            }
        }

        private void preload(int firstVisible, int lastVisible, int totalCount, int delay) {
            if (lastFirstVisibleItem == firstVisible || preloader == null) {
                return;
            }

            int start;
            int end;
            if (firstVisible > lastFirstVisibleItem) {
                // Scrolling down
                start = lastVisible + 1;
                end = start + preloadCount * numColumns;
            } else {
                // Scrolling up
                start = firstVisible - 1;
                end = start - preloadCount * numColumns;
            }

            if (start > 0 && end < totalCount) {
                // Valid preload, clamp end
                end = Math.max(0, Math.min(totalCount - 1, end));
                Direction direction = firstVisible > lastFirstVisibleItem ? Direction.DOWN : Direction.UP;

                Message m = handler.obtainMessage(MESSAGE_PRELOAD, start, end, direction);
                handler.sendMessageDelayed(m, delay);
            }

            lastFirstVisibleItem = firstVisible;
        }
    }

    private class FingerTracker implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            int action = event.getAction() & MotionEvent.ACTION_MASK;
            fingerUp = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;

            return false;
        }
    }

    private class PreloaderHandler {
        private int lastPreloaded = -1;
        private Direction lastDirection;

        public void preload(Direction direction, int start, int end) {
            if (direction == lastDirection) {
                if (end == lastPreloaded) {
                    // Repeat request, ignore
                    return;
                }

                // Shrink range to exclude previously preloaded
                if (direction == Direction.DOWN && lastPreloaded > start && lastPreloaded + 1 < listView.getCount()) {
                    start = lastPreloaded + 1;
                } else if (direction == Direction.UP && lastPreloaded < start && lastPreloaded - 1 > 0) {
                    start = lastPreloaded - 1;
                }
            }

            if (start != end) {
                if (Logger.VERBOSE) Log.d(TAG, "Preloading " + start + "-" + end);
            } else {
                if (Logger.VERBOSE) Log.d(TAG, "Preloading " + start);
            }

            if (direction == Direction.DOWN) {
                for (int i = start; i <= end; i++) {
                    preloader.preload(i);
                }
            } else if (direction == Direction.UP) {
                for (int i = start; i >= end; i--) {
                    preloader.preload(i);
                }
            }

            lastPreloaded = end;
            lastDirection = direction;
        }
    }

    private class AdapterPreloader implements Preloader {
        private Adapter adapter;
        private View[] views;

        private AdapterPreloader(Adapter adapter) {
            this.adapter = adapter;

            views = new View[adapter.getViewTypeCount()];
        }

        @Override
        public void preload(int position) {
            interceptLoads = true;

            int id = adapter.getItemViewType(position);
            if (id != Adapter.IGNORE_ITEM_VIEW_TYPE) {
                views[id] = adapter.getView(position, views[id], listView);
            } else {
                adapter.getView(position, null, listView);
            }

            interceptLoads = false;
        }
    }

    private enum Direction {
        UP, DOWN
    }
}
