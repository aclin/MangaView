package com.timwu.MangaView;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.GestureDetector.SimpleOnGestureListener;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.animation.DecelerateInterpolator;

public class View extends SurfaceView implements SurfaceHolder.Callback {
	private static final String TAG = View.class.getSimpleName();
	private static final float PAGE_TURN_THRESHOLD = 0.2f;
	private static final long ANIMATION_DURATION = 500;
	
	private MangaVolume vol;
	private int page;
	private DrawingThread drawingThread;
	private GestureDetector gestureDetector;
	
	private class DrawingThread extends Thread {
		private SimpleAnimation animation;
		private Boolean redraw = false;
		private boolean touchDown = false;
		private boolean pageFetch = false;
		private boolean magnify = false;
		private Bitmap left, right, center;
		private int xOff;
		private float xOnClick;
		private float yOnClick;
		
		@Override
		public void run() {
			while(!isInterrupted()) {
				synchronized(this) {
					processPages();
					processXOff();
					doAnimation();
					if (redraw || magnify) {
						doDraw();
						redraw = false;
					}
				}
			}
		}
		
		private void doDraw() {
			Canvas canvas = null;
			int adjXOnClick;
			int adjYOnClick;
			
			Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
		    Rect mRectSrc = new Rect();
		    Rect mRectDst = new Rect();
			
			try {
				canvas = getHolder().lockCanvas();
				canvas.clipRect(0, 0, getWidth(), getHeight());
				canvas.drawColor(Color.BLACK);
				synchronized(getHolder()) {
					if (!magnify) {
						if (left != null) {
							canvas.drawBitmap(left, xOff - getWidth(), 
									(getHeight() - left.getHeight()) / 2, null);
						}
						if (center != null) {
							canvas.drawBitmap(center, xOff, 
									(getHeight() - center.getHeight()) / 2, null);
						}
						if (right != null) {
							canvas.drawBitmap(right, xOff + getWidth(), 
									(getHeight() - right.getHeight()) / 2, null);
						}
					} else {
						canvas.drawBitmap(center, xOff,
								(getHeight() - center.getHeight()) / 2, null);
						
						adjXOnClick = ((int) xOnClick) + xOff;
						adjYOnClick = ((int) yOnClick) - (getHeight() - center.getHeight()) / 2;
						
						if (adjXOnClick + 15 > getWidth()) { // source rectangle is hitting the right edge
							mRectSrc.right = getWidth();
							mRectSrc.left = getWidth() - 30;
						} else if (adjXOnClick - 15 < 0) { // source rectangle is hitting the left edge
							mRectSrc.right = 30;
							mRectSrc.left = 0;
						} else { // source rectangle can be fully drawn from left to right
							mRectSrc.right = adjXOnClick + 15;
							mRectSrc.left = adjXOnClick - 15;
						}
						
						if (adjYOnClick + 15 > getHeight()) { // source rectangle is hitting the bottom edge
							mRectSrc.bottom = getHeight();
							mRectSrc.top = getHeight() - 30;
						} else if (adjYOnClick - 15 < 0) { // source rectangle is hitting the top edge
							mRectSrc.bottom = 30;
							mRectSrc.top = 0;
						} else { // source rectangle can be fully drawn from top to bottom
							mRectSrc.bottom = adjYOnClick + 15;
							mRectSrc.top = adjYOnClick - 15;
						}
						
						if (xOnClick + 45 > getWidth()) { // destination rectangle is hitting the right edge
							mRectDst.right = getWidth();
							mRectDst.left = getWidth() - 90;
						} else if (xOnClick - 45 < 0) { // destination rectangle is hitting the left edge
							mRectDst.right = 90;
							mRectDst.left = 0;
						} else { // destination rectangle can be fully drawn from left to right
							mRectDst.right = (int) (xOnClick + 45);
							mRectDst.left = (int) (xOnClick - 45);
						}
						
						if (yOnClick + 45 > getHeight()) { // destination rectangle is hitting the bottom edge
							mRectDst.bottom = getHeight();
							mRectDst.top = getHeight() - 90;
						} else if (yOnClick - 45 < 0) { // destination rectangle is hitting the top edge
							mRectDst.bottom = 90;
							mRectDst.top = 0;
						} else { // destination rectangle can be fully drawn from top to bottom
							mRectDst.bottom = (int) (yOnClick + 45);
							mRectDst.top = (int) (yOnClick - 45);
						}
						
						canvas.drawBitmap(center, mRectSrc, mRectDst, mPaint);
					}
				}
			} finally {
				getHolder().unlockCanvasAndPost(canvas);
			}
		}
		
		private void processXOff() {
			// Clamp X to not fall off available pages
			if (right == null) xOff = xOff < 0 ? 0 : xOff;
			if (left == null) xOff = xOff > 0 ? 0 : xOff;
						
			if (!touchDown && animation == null) {
				// Setup the animation to either turn the page, or re-center the page.
				if (xOff < -getWidth() * PAGE_TURN_THRESHOLD) {
					Log.i(TAG, "Flipping to right page. xOff " + xOff);
					animation = new SimpleAnimation(new DecelerateInterpolator(), ANIMATION_DURATION, xOff, -getWidth());
				} else if (xOff > getWidth() * PAGE_TURN_THRESHOLD) {
					Log.i(TAG, "Flipping to left page. xOff " + xOff);
					animation = new SimpleAnimation(new DecelerateInterpolator(), ANIMATION_DURATION, xOff, getWidth());
				} else if (xOff != 0) {
					Log.i(TAG, "Recentering page. xOff " + xOff);
					animation = new SimpleAnimation(new DecelerateInterpolator(), ANIMATION_DURATION, xOff, 0);
				}
			}
		}
		
		private void doAnimation() {
			if (animation == null) return;
			xOff = (int) animation.getCurrent();
			if (animation.isDone()) animation = null;
			redraw = true;
		}
		
		private void processPages() {
			if (pageFetch) {
				left = getScaledPage(page + 1);
				center = getScaledPage(page);
				right = getScaledPage(page - 1);
				pageFetch = false;
				resetXOff();
				requestRedraw();
			}
			// Detect a page change
			if (xOff >= getWidth()) {
				Log.i(TAG, "shifting page left.");
				page++;
				right = center;
				center = left;
				left = getScaledPage(page + 1);
				xOff -= getWidth();
			} else if (xOff <= -getWidth()) {
				Log.i(TAG, "shifting page right.");
				page--;
				left = center;
				center = right;
				right = getScaledPage(page - 1);
				xOff += getWidth();
			}
		}
		
		private synchronized void cancelAnimation() {
			if (animation == null) return;
			xOff = (int) animation.getCurrent();
			animation = null;
		}
		
		private synchronized void requestRedraw() {
			redraw = true;
		}
		
		private synchronized void setTouchDown(boolean newTouchDown) {
			touchDown = newTouchDown;
		}
		
		private synchronized void setMagnify(boolean newMagnify) {
			magnify = newMagnify;
		}
		
		private synchronized void setCoords(float x, float y) {
			xOnClick = x;
			yOnClick = y;
		}
		
		private synchronized void resetXOff() {
			xOff = 0;
			redraw = true;
		}
		
		private synchronized void scroll(int dx, int dy) {
			if (!magnify) {
				xOff -= dx;
				redraw = true;
			} else {
				xOnClick -= dx;
				yOnClick -= dy;
			}
		}
		
		private synchronized void requestPageFetch() {
			pageFetch = true;
		}
		
		private synchronized boolean isMagnifierOn() {
			return magnify;
		}
	}
	
	private class GestureListener extends SimpleOnGestureListener {
		@Override
		public boolean onDown(MotionEvent e) {
			drawingThread.setCoords(e.getX(), e.getY());
			drawingThread.setTouchDown(true);
			drawingThread.cancelAnimation();
			return true;
		}

		@Override
		public boolean onScroll(MotionEvent e1, MotionEvent e2,
				float distanceX, float distanceY) {
			drawingThread.scroll((int) distanceX, (int) distanceY);
			return true;
		}
		
		@Override
		public boolean onSingleTapConfirmed(MotionEvent e) {
			if (!drawingThread.isMagnifierOn()) {
				drawingThread.setMagnify(true);
			} else {
				drawingThread.setMagnify(false);
				drawingThread.requestRedraw();
			}
			return true;
		}
	}
	
	public View(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		init(context);
	}

	public View(Context context, AttributeSet attrs) {
		super(context, attrs);
		init(context);
	}

	public View(Context context) {
		super(context);
		init(context);
	}
	
	private void init(Context context) {
		getHolder().addCallback(this);
		drawingThread = new DrawingThread();
		gestureDetector = new GestureDetector(context, new GestureListener());
	}
	
	public void setVolume(MangaVolume vol) {
		this.vol = vol;
		setPage(0);
	}
	
	public void setPage(int newPage) {
		page = newPage;
		drawingThread.requestPageFetch();
	}
	
	private Bitmap getScaledPage(int page) {
		Bitmap src = vol.getPageBitmap(page);
		if (src == null) return null;
		float scale = getWidth() / (1.0f * src.getWidth());
		return Bitmap.createScaledBitmap(src, getWidth(), (int) (scale * src.getHeight()), false);
	}
	
	public int getPage() {
		return page;
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (event.getAction() == MotionEvent.ACTION_UP) {
			// Catch the touch up and update state so the drawingThread knows to animate.
			drawingThread.setTouchDown(false);
		}
		return gestureDetector.onTouchEvent(event);
	}

	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width,
			int height) {
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) {
		drawingThread.start();
	}

	@Override
	public void surfaceDestroyed(SurfaceHolder holder) {
		drawingThread.interrupt();
	}
}
