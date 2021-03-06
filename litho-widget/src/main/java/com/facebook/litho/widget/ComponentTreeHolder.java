/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.litho.widget;

import android.support.annotation.IntDef;
import android.support.v4.util.Pools;
import com.facebook.litho.Component;
import com.facebook.litho.ComponentContext;
import com.facebook.litho.ComponentTree;
import com.facebook.litho.ComponentTree.MeasureListener;
import com.facebook.litho.LayoutHandler;
import com.facebook.litho.Size;
import com.facebook.litho.StateHandler;
import java.util.concurrent.atomic.AtomicInteger;
import com.facebook.litho.TreeProps;
import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A class used to store the data backing a {@link RecyclerBinder}. For each item the
 * ComponentTreeHolder keeps the {@link RenderInfo} which contains the original {@link Component}
 * and either the {@link ComponentTree} or the {@link StateHandler} depending upon whether the item
 * is within the current working range or not.
 */
@ThreadSafe
public class ComponentTreeHolder {
  private static final int UNINITIALIZED = -1;
  private static final Pools.SynchronizedPool<ComponentTreeHolder> sComponentTreeHoldersPool =
      new Pools.SynchronizedPool<>(8);
  private ComponentTreeMeasureListenerFactory mComponentTreeMeasureListenerFactory;

  @GuardedBy("this")
  private int mLastMeasuredHeight;

  @GuardedBy("this")
  private @Nullable ComponentTree mComponentTree;

  @GuardedBy("this")
  private StateHandler mStateHandler;

  @GuardedBy("this")
  private RenderInfo mRenderInfo;

  @GuardedBy("this")
  private @Nullable ComponentTree.NewLayoutStateReadyListener mPendingNewLayoutListener;

  @GuardedBy("this")
  private int mLastRequestedWidthSpec = UNINITIALIZED;

  @GuardedBy("this")
  private int mLastRequestedHeightSpec = UNINITIALIZED;

  @IntDef({RENDER_UNINITIALIZED, RENDER_ADDED, RENDER_DRAWN})
  public @interface RenderState {}

  static final int RENDER_UNINITIALIZED = 0;
  static final int RENDER_ADDED = 1;
  static final int RENDER_DRAWN = 2;

  private final AtomicInteger mRenderState = new AtomicInteger(RENDER_UNINITIALIZED);

  private boolean mIsTreeValid;
  private LayoutHandler mLayoutHandler;
  private boolean mCanPrefetchDisplayLists;
  private boolean mCanCacheDrawingDisplayLists;
  private LayoutHandler mPreallocateMountContentHandler;
  private boolean mCanPreallocateOnDefaultHandler;
  private boolean mShouldPreallocatePerMountSpec;
  private String mSplitLayoutTag;
  private boolean mIsInserted = true;
  private boolean mHasMounted = false;

  interface ComponentTreeMeasureListenerFactory {
    MeasureListener create(ComponentTreeHolder holder);
  }

  public static ComponentTreeHolder acquire(
      RenderInfo renderInfo,
      LayoutHandler layoutHandler,
      boolean canPrefetchDisplayLists,
      boolean canCacheDrawingDisplayLists) {
    return acquire(
        renderInfo,
        layoutHandler,
        canPrefetchDisplayLists,
        canCacheDrawingDisplayLists,
        null, /* preallocateMountContentHandler */
        false, /* canPreallocateOnDefaultHandler */
        false, /* shouldPreallocatePerMountSpec */
        null,
        null);
  }

  public static ComponentTreeHolder acquire(
      RenderInfo renderInfo,
      LayoutHandler layoutHandler,
      boolean canPrefetchDisplayLists,
      boolean canCacheDrawingDisplayLists,
      ComponentTreeMeasureListenerFactory componentTreeMeasureListenerFactory,
      String splitLayoutTag) {
    return acquire(
        renderInfo,
        layoutHandler,
        canPrefetchDisplayLists,
        canCacheDrawingDisplayLists,
        null, /* preallocateMountContentHandler */
        false, /* canPreallocateOnDefaultHandler */
        false, /* shouldPreallocatePerMountSpec */
        componentTreeMeasureListenerFactory,
        splitLayoutTag);
  }

  public static ComponentTreeHolder acquire(
      RenderInfo renderInfo,
      LayoutHandler layoutHandler,
      boolean canPrefetchDisplayLists,
      boolean canCacheDrawingDisplayLists,
      @Nullable LayoutHandler preallocateMountContentHandler,
      boolean canPreallocateOnDefaultHandler,
      boolean shouldPreallocatePerMountSpec) {
    return acquire(
        renderInfo,
        layoutHandler,
        canPrefetchDisplayLists,
        canCacheDrawingDisplayLists,
        preallocateMountContentHandler,
        canPreallocateOnDefaultHandler,
        shouldPreallocatePerMountSpec,
        null,
        null);
  }

  // If we obtain the tree holder from the pool, we can rely on it being synchronized,
  // if we create the object from scratch, we do not need to worry about sharing.
  @SuppressWarnings("GuardedBy")
  public static ComponentTreeHolder acquire(
      RenderInfo renderInfo,
      LayoutHandler layoutHandler,
      boolean canPrefetchDisplayLists,
      boolean canCacheDrawingDisplayLists,
      @Nullable LayoutHandler preallocateMountContentHandler,
      boolean canPreallocateOnDefaultHandler,
      boolean shouldPreallocatePerMountSpec,
      final ComponentTreeMeasureListenerFactory componentTreeMeasureListenerFactory,
      String splitLayoutTag) {
    ComponentTreeHolder componentTreeHolder = sComponentTreeHoldersPool.acquire();
    if (componentTreeHolder == null) {
      componentTreeHolder = new ComponentTreeHolder();
    }
    componentTreeHolder.mRenderInfo = renderInfo;
    componentTreeHolder.mLayoutHandler = layoutHandler;
    componentTreeHolder.mCanPrefetchDisplayLists = canPrefetchDisplayLists;
    componentTreeHolder.mCanCacheDrawingDisplayLists = canCacheDrawingDisplayLists;
    componentTreeHolder.mPreallocateMountContentHandler = preallocateMountContentHandler;
    componentTreeHolder.mCanPreallocateOnDefaultHandler = canPreallocateOnDefaultHandler;
    componentTreeHolder.mShouldPreallocatePerMountSpec = shouldPreallocatePerMountSpec;
    componentTreeHolder.mComponentTreeMeasureListenerFactory = componentTreeMeasureListenerFactory;
    componentTreeHolder.mSplitLayoutTag = splitLayoutTag;

    return componentTreeHolder;
  }

  public synchronized void acquireStateAndReleaseTree() {
    acquireStateHandler();
    acquireAnimationState();
    releaseTree();
  }

  synchronized void invalidateTree() {
    mIsTreeValid = false;
  }

  synchronized void clearStateHandler() {
    mStateHandler = null;
  }

  synchronized void setNewLayoutReadyListener(
      @Nullable ComponentTree.NewLayoutStateReadyListener listener) {
    if (mComponentTree != null) {
      mComponentTree.setNewLayoutStateReadyListener(listener);
    } else {
      mPendingNewLayoutListener = listener;
    }
  }

  public void computeLayoutSync(
      ComponentContext context, int widthSpec, int heightSpec, Size size) {

    final ComponentTree componentTree;
    final Component component;
    final TreeProps treeProps;

    synchronized (this) {
      if (mRenderInfo.rendersView()) {
        // Nothing to do for views.
        return;
      }

      mLastRequestedWidthSpec = widthSpec;
      mLastRequestedHeightSpec = heightSpec;

      ensureComponentTree(context);

      componentTree = mComponentTree;
      component = mRenderInfo.getComponent();
      treeProps =
          mRenderInfo instanceof TreePropsWrappedRenderInfo
              ? ((TreePropsWrappedRenderInfo) mRenderInfo).getTreeProps()
              : null;
    }

    componentTree.setRootAndSizeSpec(component, widthSpec, heightSpec, size, treeProps);

    synchronized (this) {
      if (componentTree == mComponentTree && component == mRenderInfo.getComponent()) {
        mIsTreeValid = true;
        if (size != null) {
          mLastMeasuredHeight = size.height;
        }
      }
    }
  }

  public void computeLayoutAsync(ComponentContext context, int widthSpec, int heightSpec) {

    final ComponentTree componentTree;
    final Component component;
    final TreeProps treeProps;

    synchronized (this) {
      if (mRenderInfo.rendersView()) {
        // Nothing to do for views.
        return;
      }

      mLastRequestedWidthSpec = widthSpec;
      mLastRequestedHeightSpec = heightSpec;

      ensureComponentTree(context);

      componentTree = mComponentTree;
      component = mRenderInfo.getComponent();

      treeProps =
          mRenderInfo instanceof TreePropsWrappedRenderInfo
              ? ((TreePropsWrappedRenderInfo) mRenderInfo).getTreeProps()
              : null;
    }

    componentTree.setRootAndSizeSpecAsync(component, widthSpec, heightSpec, treeProps);

    synchronized (this) {
      if (mComponentTree == componentTree && component == mRenderInfo.getComponent()) {
        mIsTreeValid = true;
      }
    }
  }

  public synchronized RenderInfo getRenderInfo() {
    return mRenderInfo;
  }

  public synchronized boolean isTreeValid() {
    return mIsTreeValid;
  }

  public synchronized @Nullable ComponentTree getComponentTree() {
    return mComponentTree;
  }

  public synchronized void setRenderInfo(RenderInfo renderInfo) {
    invalidateTree();
    mRenderInfo = renderInfo;
  }

  synchronized int getMeasuredHeight() {
    return mLastMeasuredHeight;
  }

  synchronized void setMeasuredHeight(int height) {
    mLastMeasuredHeight = height;
  }

  synchronized void checkWorkingRangeAndDispatch(
      int position,
      int firstVisibleIndex,
      int lastVisibleIndex,
      int firstFullyVisibleIndex,
      int lastFullyVisibleIndex) {
    if (mComponentTree != null) {
      mComponentTree.checkWorkingRangeAndDispatch(
          position,
          firstVisibleIndex,
          lastVisibleIndex,
          firstFullyVisibleIndex,
          lastFullyVisibleIndex);
    }
  }

  int getRenderState() {
    return mRenderState.get();
  }

  void setRenderState(@RenderState int renderState) {
    mRenderState.set(renderState);
  }

  public synchronized boolean hasCompletedLatestLayout() {
    return mRenderInfo.rendersView()
        || (mComponentTree != null
            && mComponentTree.hasCompatibleLayout(
                mLastRequestedWidthSpec, mLastRequestedHeightSpec));
  }

  /** @return whether this ComponentTreeHolder has been inserted into the adapter yet. */
  public synchronized boolean isInserted() {
    return mIsInserted;
  }

  /** Set whether this ComponentTreeHolder has been inserted into the adapter. */
  public synchronized void setInserted(boolean inserted) {
    mIsInserted = inserted;
  }

  public synchronized void release() {
    releaseTree();
    clearStateHandler();
    mRenderInfo = null;
    mLayoutHandler = null;
    mCanPrefetchDisplayLists = false;
    mCanCacheDrawingDisplayLists = false;
    mPreallocateMountContentHandler = null;
    mShouldPreallocatePerMountSpec = false;
    mCanPreallocateOnDefaultHandler = false;
    sComponentTreeHoldersPool.release(this);
    mPendingNewLayoutListener = null;
    mLastRequestedWidthSpec = UNINITIALIZED;
    mLastRequestedHeightSpec = UNINITIALIZED;
    mIsInserted = true;
    mHasMounted = false;
    mRenderState.set(RENDER_UNINITIALIZED);
  }

  @GuardedBy("this")
  private void ensureComponentTree(ComponentContext context) {
    if (mComponentTree == null) {
      final Object clipChildrenAttr = mRenderInfo.getCustomAttribute(RenderInfo.CLIP_CHILDREN);
      final boolean clipChildren = clipChildrenAttr == null ? true : (boolean) clipChildrenAttr;
      mComponentTree =
          ComponentTree.create(context, mRenderInfo.getComponent())
              .layoutThreadHandler(mLayoutHandler)
              .stateHandler(mStateHandler)
              .canPrefetchDisplayLists(mCanPrefetchDisplayLists)
              .canCacheDrawingDisplayLists(mCanCacheDrawingDisplayLists)
              .shouldClipChildren(clipChildren)
              .preAllocateMountContentHandler(mPreallocateMountContentHandler)
              .preallocateOnDefaultHandler(mCanPreallocateOnDefaultHandler)
              .shouldPreallocateMountContentPerMountSpec(mShouldPreallocatePerMountSpec)
              .measureListener(
                  mComponentTreeMeasureListenerFactory == null
                      ? null
                      : mComponentTreeMeasureListenerFactory.create(this))
              .splitLayoutTag(mSplitLayoutTag)
              .hasMounted(mHasMounted)
              .build();
      if (mPendingNewLayoutListener != null) {
        mComponentTree.setNewLayoutStateReadyListener(mPendingNewLayoutListener);
      }
    }
  }

  @GuardedBy("this")
  private void releaseTree() {
    if (mComponentTree != null) {
      mComponentTree.release();
      mComponentTree = null;
    }

    mIsTreeValid = false;
  }

  @GuardedBy("this")
  private void acquireStateHandler() {
    if (mComponentTree == null) {
      return;
    }

    mStateHandler = mComponentTree.acquireStateHandler();
  }

  @GuardedBy("this")
  private void acquireAnimationState() {
    if (mComponentTree == null) {
      return;
    }

    mHasMounted = mComponentTree.hasMounted();
  }
}
