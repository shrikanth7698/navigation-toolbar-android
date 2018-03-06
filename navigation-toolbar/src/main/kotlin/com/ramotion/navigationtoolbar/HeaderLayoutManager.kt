package com.ramotion.navigationtoolbar

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PointF
import android.os.Build
import android.os.Looper
import android.support.design.widget.AppBarLayout
import android.support.design.widget.CoordinatorLayout
import android.support.v4.view.ViewCompat
import android.util.AttributeSet
import android.util.SparseArray
import android.util.TypedValue
import android.view.View
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.min


/**
 * Moves header's views
 */
class HeaderLayoutManager(context: Context, attrs: AttributeSet?)
    : CoordinatorLayout.Behavior<HeaderLayout>(context, attrs), AppBarLayout.OnOffsetChangedListener {

    enum class Orientation {
        HORIZONTAL, VERTICAL, TRANSITIONAL
    }

    enum class ScrollState {
        IDLE, DRAGGING, FLING
    }

    enum class VerticalGravity(val value: Int) {
        LEFT(-1),
        CENTER(-2),
        RIGHT(-3);

        companion object {
            private val map = VerticalGravity.values().associateBy(VerticalGravity::value)
            fun fromInt(type: Int, defaultValue: VerticalGravity = RIGHT) = map.getOrElse(type, {defaultValue})
        }
    }

    data class Point(val x: Int, val y: Int)

    internal companion object {
        const val TAB_ON_SCREEN_COUNT = 5
        const val TAB_OFF_SCREEN_COUNT = 1
        const val VERTICAL_TAB_WIDTH_RATIO = 4f / 5f
        const val SCROLL_STOP_CHECK_DELAY = 100L
        const val COLLAPSING_BY_SELECT_DURATION = 500
        const val SNAP_ANIMATION_DURATION = 300L
        const val MAX_SCROLL_DURATION = 600L
    }

    interface HeaderChangeListener {
        fun onHeaderChanged(lm: HeaderLayoutManager, header: HeaderLayout, headerBottom: Int)
    }

    interface HeaderUpdateListener {
        fun onHeaderUpdated(lm: HeaderLayoutManager, header: HeaderLayout, headerBottom: Int)
    }

    interface ItemClickListener {
        fun onItemClicked(viewHolder: HeaderLayout.ViewHolder)
    }

    interface ItemChangeListener {
        fun onItemChanged(position: Int)
    }

    interface ScrollStateListener {
        fun onScrollStateChanged(state: HeaderLayoutManager.ScrollState)
    }

    private val tabOffsetCount = TAB_OFF_SCREEN_COUNT
    private val viewCache = SparseArray<View?>()
    private val viewFlinger = ViewFlinger(context)
    private val verticalGravity: VerticalGravity
    private val collapsingBySelectDuration: Int
    private val tabOnScreenCount: Int
    private val centerIndex: Int
    private val topSnapDistance: Int
    private val bottomSnapDistance: Int

    val screenWidth = context.resources.displayMetrics.widthPixels
    val screenHeight = context.resources.displayMetrics.heightPixels
    val screenHalf = screenHeight / 2f
    val horizontalTabWidth = screenWidth
    val horizontalTabHeight = screenHalf.toInt()

    val topBorder: Int
    val workHeight: Int
    val verticalTabWidth: Int
    val verticalTabHeight: Int

    internal val appBarBehavior = AppBarBehavior()
    internal val scrollListener = HeaderScrollListener()
    internal val changeListener = mutableListOf<HeaderChangeListener>()
    internal val updateListener = mutableListOf<HeaderUpdateListener>()
    internal val itemClickListeners = mutableListOf<ItemClickListener>()
    internal val itemChangeListeners = mutableListOf<ItemChangeListener>()
    internal val scrollStateListeners = mutableListOf<ScrollStateListener>()

    private var offsetAnimator: ValueAnimator? = null
    private var appBar: AppBarLayout? = null
    private var headerLayout: HeaderLayout? = null

    private var isInitialized = false
    private var isCanDrag = true
    private var isOffsetChanged = false
    private var isCheckingScrollStop =false

    private var scrollState = ScrollState.IDLE
    private var curOrientation: Orientation? = null

    var hPoint: Point? = null
    var vPoint: Point? = null

    inner class AppBarBehavior : AppBarLayout.Behavior() {
        init {
            setDragCallback(object : AppBarLayout.Behavior.DragCallback() {
                override fun canDrag(appBarLayout: AppBarLayout) = isCanDrag
            })
        }
    }

    inner class HeaderScrollListener : HeaderLayout.ScrollListener {
        override fun onItemClick(header: HeaderLayout, viewHolder: HeaderLayout.ViewHolder) =
                this@HeaderLayoutManager.onHeaderItemClick(header, viewHolder)

        override fun onHeaderDown(header: HeaderLayout) =
                this@HeaderLayoutManager.onHeaderDown(header)

        override fun onHeaderUp(header: HeaderLayout)  =
                this@HeaderLayoutManager.onHeaderUp(header)

        override fun onHeaderHorizontalScroll(header: HeaderLayout, distance: Float) =
                this@HeaderLayoutManager.onHeaderHorizontalScroll(header, distance)

        override fun onHeaderVerticalScroll(header: HeaderLayout, distance: Float) =
                this@HeaderLayoutManager.onHeaderVerticalScroll(header, distance)

        override fun onHeaderHorizontalFling(header: HeaderLayout, velocity: Float) =
                this@HeaderLayoutManager.onHeaderHorizontalFling(header, velocity)

        override fun onHeaderVerticalFling(header: HeaderLayout, velocity: Float) =
                this@HeaderLayoutManager.onHeaderVerticalFling(header, velocity)
    }

    private inner class ViewFlinger(context: Context) : Runnable {
        private val scroller = OverScroller(context)

        override fun run() {
            val header = headerLayout ?: return

            val x = scroller.currX
            val y = scroller.currY

            if (!scroller.computeScrollOffset()) {
                setScrollState(ScrollState.IDLE)
                return
            }

            val diffX = scroller.currX - x
            val diffY = scroller.currY - y

            if (diffX == 0 && diffY == 0) {
                ViewCompat.postOnAnimation(header, this)
                return
            }

            for (i in 0 until header.childCount) {
                val child = header.getChildAt(i)
                child.offsetLeftAndRight(diffX)
                child.offsetTopAndBottom(diffY)
            }

            fill(header)

            ViewCompat.postOnAnimation(header, this)
        }

        fun fling(startX: Int, startY: Int, velocityX: Int, velocityY: Int, minX: Int, maxX: Int, minY: Int, maxY: Int) {
            setScrollState(ScrollState.FLING)
            scroller.forceFinished(true)
            scroller.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY)
            ViewCompat.postOnAnimation(headerLayout, this)
        }

        fun startScroll(startX: Int, startY: Int, dx: Int, dy: Int, duration: Int) {
            setScrollState(ScrollState.FLING)
            scroller.forceFinished(true)
            scroller.startScroll(startX, startY, dx, dy, duration)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1) {
                scroller.computeScrollOffset()
            }
            ViewCompat.postOnAnimation(headerLayout, this)
        }

        fun stop() {
            if (!scroller.isFinished) {
                setScrollState(ScrollState.IDLE)
                scroller.abortAnimation()
            }
        }
    }

    init {
        Looper.myQueue().addIdleHandler {
            if (isOffsetChanged && !isCheckingScrollStop) {
                checkIfOffsetChangingStopped()
            }
            true
        }

        var itemCount = TAB_ON_SCREEN_COUNT
        var verticalItemWidth = screenWidth * VERTICAL_TAB_WIDTH_RATIO
        var gravity = VerticalGravity.RIGHT
        var collapsingDuration = COLLAPSING_BY_SELECT_DURATION

        attrs?.also {
            val a = context.theme.obtainStyledAttributes(attrs, R.styleable.NavigationToolBarr, 0, 0)
            try {
                itemCount = a.getInteger(R.styleable.NavigationToolBarr_headerItemsCount, -1)
                        .let { if (it <= 0) TAB_ON_SCREEN_COUNT else it }

                gravity = VerticalGravity.fromInt(a.getInteger(R.styleable.NavigationToolBarr_headerVerticalGravity, VerticalGravity.RIGHT.value))

                collapsingDuration = a.getInteger(R.styleable.NavigationToolBarr_headerCollapsingBySelectDuration, COLLAPSING_BY_SELECT_DURATION)

                if (a.hasValue(R.styleable.NavigationToolBarr_headerVerticalItemWidth)) {
                    verticalItemWidth = if (a.getType(R.styleable.NavigationToolBarr_headerVerticalItemWidth) == TypedValue.TYPE_DIMENSION) {
                        a.getDimension(R.styleable.NavigationToolBarr_headerVerticalItemWidth, verticalItemWidth)
                    } else {
                        screenWidth.toFloat()
                    }
                }
            } finally {
                a.recycle()
            }
        }

        tabOnScreenCount = itemCount
        centerIndex = tabOnScreenCount / 2
        verticalTabHeight = (screenHeight * (1f / tabOnScreenCount)).toInt()
        verticalTabWidth = verticalItemWidth.toInt()
        verticalGravity = gravity
        collapsingBySelectDuration = collapsingDuration

        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        val statusBarHeight = if (resourceId > 0) {
            context.resources.getDimensionPixelSize(resourceId)
        } else 0

        val actionBarSize: Int
        val styledAttributes = context.theme.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        try {
            actionBarSize = styledAttributes.getDimension(0, 0f).toInt()
        } finally {
            styledAttributes.recycle()
        }

        topBorder = actionBarSize + statusBarHeight
        workHeight = screenHeight - topBorder

        topSnapDistance = (topBorder + (screenHalf - topBorder) / 2).toInt()
        bottomSnapDistance = (screenHalf + screenHalf / 2).toInt()
    }

    override fun layoutDependsOn(parent: CoordinatorLayout, child: HeaderLayout, dependency: View): Boolean {
        return dependency is AppBarLayout
    }

    override fun onLayoutChild(parent: CoordinatorLayout, header: HeaderLayout, layoutDirection: Int): Boolean {
        if (!parent.isLaidOut) {
            parent.onLayoutChild(header, layoutDirection)

            appBar = parent.findViewById(R.id.com_ramotion_app_bar)

            headerLayout = header
            header.scrollListener = scrollListener

            initPoints(header)
            fill(header)

            isInitialized = true
        }

        return true
    }

    override fun onDependentViewChanged(parent: CoordinatorLayout, header: HeaderLayout, dependency: View): Boolean {
        val headerBottom = dependency.bottom
        header.y = (headerBottom - header.height).toFloat() // Offset header on collapsing
        curOrientation = null
        viewFlinger.stop()
        changeListener.forEach { it.onHeaderChanged(this, header, headerBottom) }
        return true
    }

    override fun onOffsetChanged(appBarLayout: AppBarLayout?, verticalOffset: Int) {
        isOffsetChanged = true
    }

    // TODO: fun scroll(distance)

    fun scrollToPosition(pos: Int) {
        val header = headerLayout ?: return
        if (header.childCount == 0) {
            return
        }

        val itemCount = header.adapter?.getItemCount() ?: -1
        if (pos < 0 || pos > itemCount) {
            return
        }

        if (header.isHorizontalScrollEnabled) {
            val anchorPos = getHorizontalAnchorPos(header)
            if (anchorPos == pos) {
                return
            }

            val offset = (pos - anchorPos) * header.getChildAt(0).width
            onHeaderHorizontalScroll(header, offset.toFloat())
        } else if (header.isVerticalScrollEnabled) {
            val anchorPos = getVerticalAnchorPos(header)
            if (anchorPos == pos) {
                return
            }

            val offset = (pos - anchorPos) * header.getChildAt(0).height
            onHeaderVerticalScroll(header, offset.toFloat())
        }

        itemChangeListeners.forEach { it.onItemChanged(pos) }
    }

    fun smoothScrollToPosition(pos: Int) {
        val hx = hPoint?.x ?: return
        val vy = vPoint?.y ?: return

        if (offsetAnimator?.isRunning == true) {
            return
        }

        val header = headerLayout ?: return
        if (header.childCount == 0) {
            return
        }

        val itemCount = header.adapter?.getItemCount() ?: -1
        if (pos < 0 || pos > itemCount) {
            return
        }

        itemChangeListeners.forEach { it.onItemChanged(pos) }

        if (header.isHorizontalScrollEnabled) {
            val anchorPos = getHorizontalAnchorPos(header)
            if (anchorPos == HeaderLayout.INVALID_POSITION) {
                return
            }

            val anchorView = getHorizontalAnchorView(header) ?: return
            val childWidth = anchorView.width
            val offset = (pos - anchorPos) * childWidth + (anchorView.left - hx)
            if (offset == 0) {
                return
            }

            val startX = getStartX(anchorView)
            val delta = abs(offset) / childWidth.toFloat()
            val duration = min(((delta + 1) * 100).toInt(), MAX_SCROLL_DURATION.toInt())
            viewFlinger.startScroll(startX, 0, -offset, 0, duration)
        } else if (header.isVerticalScrollEnabled) {
            val anchorPos = getVerticalAnchorPos(header)
            if (anchorPos == HeaderLayout.INVALID_POSITION) {
                return
            }

            val anchorView = getVerticalAnchorView(header) ?: return
            val childHeight = anchorView.height
            val offset = (pos - anchorPos) * childHeight + (anchorView.top - vy)
            if (offset == 0) {
                return
            }

            val startY = getStartY(anchorView)
            val delta = abs(offset) / childHeight.toFloat()
            val duration = min(((delta + 1) * 100).toInt(), MAX_SCROLL_DURATION.toInt())
            viewFlinger.startScroll(0, startY, 0, -offset, duration)
        }
    }

    fun getHorizontalPoint(): Point {
        return hPoint ?: throw RuntimeException("Layout manager not initialized yet")
    }

    fun getVerticalPoint(): Point {
        return vPoint ?: throw RuntimeException("Layout manager not initialized yet")
    }

    fun getHorizontalAnchorView(header: HeaderLayout): View? {
        val centerLeft = hPoint?.x ?: return null

        var result: View? = null
        var lastDiff = Int.MAX_VALUE

        for (i in 0 until header.childCount) {
            val child = header.getChildAt(i)
            val diff = Math.abs(child.left - centerLeft).toInt()
            if (diff < lastDiff) {
                lastDiff = diff
                result = child
            }
        }

        return result
    }

    fun getVerticalAnchorView(header: HeaderLayout): View? {
        val centerTop = vPoint?.y ?: return null

        var result: View? = null
        var lastDiff = Int.MAX_VALUE

        for (i in 0 until header.childCount) {
            val child = header.getChildAt(i)
            val diff = Math.abs(child.top - centerTop).toInt()
            if (diff < lastDiff) {
                lastDiff = diff
                result = child
            }
        }

        return result
    }

    fun layoutChild(child: View, x: Int, y: Int, w: Int, h: Int) {
        val ws = View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        val hs = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        child.measure(ws, hs)
        child.layout(x, y, x + w, y + h)
    }

    fun fill(header: HeaderLayout) {
        val orientation = getOrientation(::getPositionRatio)
        val pos = when (orientation) {
            Orientation.HORIZONTAL -> getHorizontalAnchorPos(header)
            Orientation.VERTICAL -> getVerticalAnchorPos(header)
            Orientation.TRANSITIONAL -> return
        }

        viewCache.clear()

        for (i in 0 until header.childCount) {
            val view = header.getChildAt(i)
            viewCache.put(header.getAdapterPosition(view), view)
        }

        for (i in 0 until viewCache.size()) {
            viewCache.valueAt(i)?.also { header.detachView(it) }
        }

        when (orientation) {
            Orientation.HORIZONTAL -> {
                fillLeft(header, pos)
                fillRight(header, pos)
            }
            Orientation.VERTICAL -> {
                fillTop(header, pos)
                fillBottom(header, pos)
            }
        }

        for (i in 0 until viewCache.size()) {
            viewCache.valueAt(i)?.also { header.recycler.recycleView(it) }
        }

        val headerBottom = (header.y + header.height).toInt()
        updateListener.forEach { it.onHeaderUpdated(this, header, headerBottom) }
    }

    fun getAnchorView(header: HeaderLayout): View? {
        val orientation = getOrientation(::getPositionRatio)
        return when (orientation) {
            Orientation.HORIZONTAL -> getHorizontalAnchorView(header)
            Orientation.VERTICAL -> getVerticalAnchorView(header)
            Orientation.TRANSITIONAL -> null
        }
    }

    fun getAnchorPos(header: HeaderLayout): Int? {
        return getAnchorView(header)?.let { HeaderLayout.getChildViewHolder(it) }?.position
    }

    private fun getHorizontalAnchorPos(header: HeaderLayout): Int {
        return getHorizontalAnchorView(header)
                ?.let { header.getAdapterPosition(it) }
                ?: HeaderLayout.INVALID_POSITION
    }

    private fun getVerticalAnchorPos(header: HeaderLayout): Int {
        return getVerticalAnchorView(header)
                ?.let { header.getAdapterPosition(it) }
                ?: HeaderLayout.INVALID_POSITION
    }

    private fun onHeaderItemClick(header: HeaderLayout, viewHolder: HeaderLayout.ViewHolder): Boolean {
        return when {
            header.isHorizontalScrollEnabled -> {
                smoothScrollToPosition(viewHolder.position)
                itemClickListeners.forEach { it.onItemClicked(viewHolder) }
                true
            }
            header.isVerticalScrollEnabled -> {
                smoothOffset(screenHalf.toInt())
                itemClickListeners.forEach { it.onItemClicked(viewHolder) }
                true
            }
            else -> false
        }
    }

    private fun onHeaderDown(header: HeaderLayout): Boolean {
        if (header.childCount == 0) {
            return false
        }

        viewFlinger.stop()
        return true
    }

    private fun onHeaderUp(header: HeaderLayout): Unit {
        if (scrollState != ScrollState.FLING) {
            setScrollState(ScrollState.IDLE)
        }
    }

    private fun onHeaderHorizontalScroll(header: HeaderLayout, distance: Float): Boolean {
        val childCount = header.childCount
        if (childCount == 0) {
            return false
        }

        setScrollState(ScrollState.DRAGGING)

        val scrollLeft = distance >= 0
        val offset = if (scrollLeft) {
            val lastRight = header.getChildAt(childCount - 1).right
            val newRight = lastRight - distance
            if (newRight > header.width) distance.toInt() else lastRight - header.width
        } else {
            val firstLeft = header.getChildAt(0).left
            if (firstLeft > 0) { // TODO: firstTop > border, border - center or systemBar height
                0
            } else {
                val newLeft = firstLeft - distance
                if (newLeft < 0) distance.toInt() else firstLeft
            }
        }

        for (i in 0 until childCount) {
            header.getChildAt(i).offsetLeftAndRight(-offset)
        }

        fill(header)
        return true
    }

    private fun onHeaderVerticalScroll(header: HeaderLayout, distance: Float): Boolean {
        val childCount = header.childCount
        if (childCount == 0) {
            return false
        }

        setScrollState(ScrollState.DRAGGING)

        val scrollUp = distance >= 0
        val offset = if (scrollUp) {
            val lastBottom = header.getChildAt(childCount - 1).bottom
            val newBottom = lastBottom - distance
            if (newBottom > header.height) distance.toInt() else lastBottom - header.height
        } else {
            val firstTop = header.getChildAt(0).top
            if (firstTop > 0) { // TODO: firstTop > border, border - center or systemBar height
                0
            } else {
                val newTop = firstTop - distance
                if (newTop < 0) distance.toInt() else firstTop
            }
        }

        for (i in 0 until childCount) {
            header.getChildAt(i).offsetTopAndBottom(-offset)
        }

        fill(header)
        return true
    }

    private fun onHeaderHorizontalFling(header: HeaderLayout, velocity: Float): Boolean {
        val childCount = header.childCount
        if (childCount == 0) {
            return false
        }

        val itemCount = header.adapter?.getItemCount() ?: return false
        val startX = getStartX(header.getChildAt(0))
        val min = -itemCount * horizontalTabWidth + header.width
        val max = 0

        viewFlinger.fling(startX, 0, velocity.toInt(), 0, min, max, 0, 0)

        return true
    }

    private fun onHeaderVerticalFling(header: HeaderLayout, velocity: Float): Boolean {
        val childCount = header.childCount
        if (childCount == 0) {
            return false
        }

        val itemCount = header.adapter?.getItemCount() ?: return false
        val startY = getStartY(header.getChildAt(0))
        val min = -itemCount * verticalTabHeight + header.height
        val max = 0

        viewFlinger.fling(0, startY, 0, velocity.toInt(), 0, 0, min, max)

        return true
    }

    private fun getStartX(view: View): Int {
        return HeaderLayout.getChildViewHolder(view)
                ?.let { view.left - it.position * horizontalTabWidth }
                ?: throw RuntimeException("View holder not found")
    }

    private fun getStartY(view: View): Int {
        return HeaderLayout.getChildViewHolder(view)
                ?.let { view.top - it.position * verticalTabHeight }
                ?: throw RuntimeException("View holder not found")
    }

    private fun initPoints(header: HeaderLayout) {
        val hx = 0
        val hy = screenHalf.toInt()
        val vy = (screenHeight / tabOnScreenCount) * centerIndex
        val vx = when (verticalGravity) {
            HeaderLayoutManager.VerticalGravity.LEFT -> 0
            HeaderLayoutManager.VerticalGravity.CENTER -> header.width - verticalTabWidth / 2
            HeaderLayoutManager.VerticalGravity.RIGHT -> header.width - verticalTabWidth
        }

        hPoint = Point(hx, hy)
        vPoint = Point(vx, vy)
    }

    private fun getPositionRatio() = appBar?.let { Math.max(0f, it.bottom / screenHeight.toFloat()) } ?: 0f

    private fun getOrientation(getRatio: () -> Float, force: Boolean = false): Orientation {
        return if (force) {
            val ratio = getRatio()
            when {
                ratio <= 0.5f -> Orientation.HORIZONTAL
                ratio < 1 -> Orientation.TRANSITIONAL
                else -> Orientation.VERTICAL
            }.also {
                curOrientation = it
            }
        } else {
            curOrientation ?: getOrientation(getRatio, true)
        }
    }

    private fun fillLeft(header: HeaderLayout, anchorPos: Int) {
        val (hx, hy) = hPoint ?: return

        if (anchorPos == HeaderLayout.INVALID_POSITION) {
            return
        }

        val top = appBar?.let { header.height - it.bottom } ?: hy
        val bottom = appBar?.bottom ?: horizontalTabHeight
        val leftDiff = hx - (viewCache.get(anchorPos)?.left ?: 0)

        var pos = Math.max(0, anchorPos - centerIndex - tabOffsetCount)
        var left = (hx - (anchorPos - pos) * horizontalTabWidth) - leftDiff

        while (pos < anchorPos) {
            val view = getPlacedChildForPosition(header, pos, left, top, horizontalTabWidth, bottom)
            left = view.right
            pos++
        }
    }

    private fun fillRight(header: HeaderLayout, anchorPos: Int) {
        val (hx, hy) = hPoint ?: return

        val count = header.adapter?.getItemCount() ?: 0
        if (count == 0) {
            return
        }

        val startPos = when (anchorPos) {
            HeaderLayout.INVALID_POSITION -> 0
            else -> anchorPos
        }

        val top = appBar?.let { header.height - it.bottom } ?: hy
        val bottom = appBar?.bottom ?: horizontalTabHeight
        val maxPos = Math.min(count, startPos + centerIndex + 1 + tabOffsetCount)

        var pos = startPos
        var left  = if (header.childCount > 0) {
            header.getChildAt(header.childCount - 1).right
        } else {
            hx
        }

        while (pos <  maxPos) {
            val view = getPlacedChildForPosition(header, pos, left, top, horizontalTabWidth, bottom)
            left = view.right
            pos++
        }
    }

    private fun fillTop(header: HeaderLayout, anchorPos: Int) {
        val (vx, vy) = vPoint ?: return

        if (anchorPos == HeaderLayout.INVALID_POSITION) {
            return
        }

        val topDiff = vy - (viewCache.get(anchorPos)?.top ?: 0)
        val left = vx

        var pos = Math.max(0, anchorPos - centerIndex - tabOffsetCount)
        var top = (vy - (anchorPos - pos) * verticalTabHeight) - topDiff

        while (pos < anchorPos) {
            val view = getPlacedChildForPosition(header, pos, left, top, verticalTabWidth, verticalTabHeight)
            top = view.bottom
            pos++
        }
    }

    private fun fillBottom(header: HeaderLayout, anchorPos: Int) {
        val (vx, vy) = vPoint ?: return

        val count = header.adapter?.getItemCount() ?: 0
        if (count == 0) {
            return
        }

        val startPos = when (anchorPos) {
            HeaderLayout.INVALID_POSITION -> 0
            else -> anchorPos
        }

        val maxPos = Math.min(count, startPos + centerIndex + 1 + tabOffsetCount)
        val left = vx
        var pos = startPos

        var top  = if (header.childCount > 0) {
            header.getChildAt(header.childCount - 1).bottom
        } else {
            vy
        }

        while (pos <  maxPos) {
            val view = getPlacedChildForPosition(header, pos, left, top, verticalTabWidth, verticalTabHeight)
            top = view.bottom
            pos++
        }
    }

    private fun getPlacedChildForPosition(header: HeaderLayout, pos: Int, x: Int, y: Int, w: Int, h: Int): View {
        val cacheView = viewCache.get(pos)
        if (cacheView != null) {
            header.attachView(cacheView)
            viewCache.remove(pos)
            return cacheView
        }

        val view = header.recycler.getViewForPosition(pos)
        header.addView(view)
        layoutChild(view, x, y, w, h)
        return view
    }

    private fun checkIfOffsetChangingStopped() {
        val header = headerLayout ?: return

        isOffsetChanged = false
        isCheckingScrollStop = true

        val startOffset = appBarBehavior.topAndBottomOffset
        header.postOnAnimationDelayed({
            isCheckingScrollStop = false
            val currentOffset = appBarBehavior.topAndBottomOffset
            val scrollStopped = currentOffset == startOffset
            if (scrollStopped) {
                onOffsetChangingStopped(currentOffset)
            }
        }, SCROLL_STOP_CHECK_DELAY)
    }

    private fun onOffsetChangingStopped(offset: Int) {
        val header = headerLayout ?: return
        val appBar = appBar ?: return

        var hScrollEnable = false
        var vScrollEnable = false

        val invertedOffset = screenHeight + offset
        when (invertedOffset) {
            screenHeight -> {
                vScrollEnable = true
                isCanDrag = false
            }
            screenHalf.toInt() -> {
                hScrollEnable = true
                isCanDrag = false
            }
            topBorder -> {
                hScrollEnable = true
                isCanDrag = true
            }
            in topBorder..(topSnapDistance - 1) -> {
                appBar.setExpanded(false, true)
            }
            in topSnapDistance..(bottomSnapDistance - 1) -> {
                smoothOffset(screenHalf.toInt(), SNAP_ANIMATION_DURATION)
            }
            else -> {
                appBar.setExpanded(true, true)
            }
        }

        header.isHorizontalScrollEnabled = hScrollEnable
        header.isVerticalScrollEnabled = vScrollEnable
    }

    private fun smoothOffset(offset: Int, duration: Long = collapsingBySelectDuration.toLong()) {
        val header = headerLayout ?: return

        offsetAnimator?.cancel()

        offsetAnimator = ValueAnimator().also { animator ->
            animator.duration = duration
            animator.setIntValues(appBarBehavior.topAndBottomOffset, -offset)
            animator.addUpdateListener {
                val value = it.animatedValue as Int
                appBarBehavior.topAndBottomOffset = value // TODO: try appBar.Behavior::onStopNestedScroll
            }
            animator.addListener(object: AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator?, isReverse: Boolean) {
                    header.isHorizontalScrollEnabled = false
                    header.isVerticalScrollEnabled = false
                }
                override fun onAnimationEnd(animation: Animator?) {
                    this@HeaderLayoutManager.onOffsetChangingStopped(-offset)
                }
            })
            animator.start()
        }
    }

    private fun setScrollState(state: ScrollState) {
        if (scrollState == state) {
            return
        }

        scrollStateListeners.forEach { it.onScrollStateChanged(state) }
        scrollState = state
    }
}