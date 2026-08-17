/*
 * Copyright 2019 Mauricio Colli <mauriciocolli@outlook.com>
 * FeedFragment.kt is part of NewPipe
 *
 * License: GPL-3.0+
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe.local.feed

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import androidx.activity.OnBackPressedCallback
import androidx.annotation.IdRes
import androidx.annotation.Nullable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.edit
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xwray.groupie.GroupieAdapter
import com.xwray.groupie.Item
import com.xwray.groupie.OnItemClickListener
import com.xwray.groupie.OnItemLongClickListener
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.feed.model.FeedGroupEntity
import org.schabi.newpipe.database.stream.StreamWithState
import org.schabi.newpipe.database.subscription.SubscriptionEntity
import org.schabi.newpipe.databinding.FragmentFeedBinding
import org.schabi.newpipe.databinding.PlaylistControlBinding
import org.schabi.newpipe.error.ErrorInfo
import org.schabi.newpipe.error.UserAction
import org.schabi.newpipe.extractor.exceptions.AccountTerminatedException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty
import org.schabi.newpipe.fragments.BaseStateFragment
import org.schabi.newpipe.info_list.ItemViewMode
import org.schabi.newpipe.info_list.dialog.InfoItemDialog
import org.schabi.newpipe.ktx.animate
import org.schabi.newpipe.ktx.animateHideRecyclerViewAllowingScrolling
import org.schabi.newpipe.ktx.slideUp
import org.schabi.newpipe.local.feed.item.StreamItem
import org.schabi.newpipe.local.feed.service.FeedLoadService
import org.schabi.newpipe.local.history.HistoryRecordManager
import org.schabi.newpipe.local.subscription.SubscriptionManager
import org.schabi.newpipe.player.PlayerService
import org.schabi.newpipe.player.playqueue.PlayQueue
import org.schabi.newpipe.player.playqueue.SinglePlayQueue
import org.schabi.newpipe.util.DeviceUtils
import org.schabi.newpipe.util.Localization
import org.schabi.newpipe.util.NavigationHelper
import org.schabi.newpipe.util.NavigationHelper.openFeedChannelsFragment
import org.schabi.newpipe.util.ThemeHelper.getGridSpanCountStreams
import org.schabi.newpipe.util.ThemeHelper.getItemViewMode
import org.schabi.newpipe.util.ThemeHelper.resolveDrawable
import org.schabi.newpipe.util.ThemeHelper.shouldUseGridLayout
import java.time.OffsetDateTime
import java.util.function.Consumer

class FeedFragment : BaseStateFragment<FeedState>() {
    private var _feedBinding: FragmentFeedBinding? = null
    private val feedBinding get() = _feedBinding!!

    private val disposables = CompositeDisposable()

    private lateinit var viewModel: FeedViewModel
    @JvmField var listState: Parcelable? = null

    private var groupId = FeedGroupEntity.GROUP_ALL_ID
    private var groupName = ""
    private var oldestSubscriptionUpdate: OffsetDateTime? = null

    private lateinit var groupAdapter: GroupieAdapter
    private lateinit var continueWatchingAdapter: GroupieAdapter
    private var historyRecordManager: HistoryRecordManager? = null

    @JvmField var showPlayedItems: Boolean = true

    private var onSettingsChangeListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var updateListViewModeOnResume = false
    private var updatePullToRefreshOnResume = false
    private var isRefreshing = false

    private var lastNewItemsCount = 0

    private var playlistControlBinding: PlaylistControlBinding? = null

    private var autoBackgroundPlaying = false
    private var randomBackgroundPlaying = false

    // Search related variables
    private var editText: EditText? = null
    private var searchClear: View? = null
    private var originalItems = mutableListOf<StreamItem>()
    private var filteredItems = mutableListOf<StreamItem>()
    private var isFilterEnabled = false
    private var isPullToRefreshEnabled = true

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
            filterItems(s.toString())
        }

        override fun afterTextChanged(s: Editable?) {}
    }

    init {
        setHasOptionsMenu(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        groupId = arguments?.getLong(KEY_GROUP_ID, FeedGroupEntity.GROUP_ALL_ID)
            ?: FeedGroupEntity.GROUP_ALL_ID
        groupName = arguments?.getString(KEY_GROUP_NAME) ?: ""

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        autoBackgroundPlaying = false
        randomBackgroundPlaying = prefs.getBoolean(getString(R.string.random_music_play_mode_key), false)

        historyRecordManager = HistoryRecordManager(context)

        onSettingsChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                getString(R.string.list_view_mode_key) -> {
                    updateListViewModeOnResume = true
                }
                getString(R.string.grid_layout_enabled_key) -> {
                    updateListViewModeOnResume = true
                }
                getString(R.string.grid_columns_key) -> {
                    updateListViewModeOnResume = true
                }
                getString(R.string.grid_columns_landscape_key) -> {
                    updateListViewModeOnResume = true
                }
                getString(R.string.pull_to_refresh_key) -> {
                    updatePullToRefreshOnResume = true
                }
            }
        }
        PreferenceManager.getDefaultSharedPreferences(activity)
            .registerOnSharedPreferenceChangeListener(onSettingsChangeListener)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_feed, container, false)
    }

    override fun onViewCreated(rootView: View, savedInstanceState: Bundle?) {
        _feedBinding = FragmentFeedBinding.bind(rootView)
        playlistControlBinding = PlaylistControlBinding.bind(feedBinding.playlistControl.root)
        super.onViewCreated(rootView, savedInstanceState)
        updatePullToRefreshState()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (activity?.supportActionBar?.customView != null) {
                    destroyCustomViewInActionBar()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        val factory = FeedViewModel.Factory(requireContext(), groupId)
        viewModel = ViewModelProvider(this, factory).get(FeedViewModel::class.java)
        showPlayedItems = viewModel.getShowPlayedItemsFromPreferences()
        viewModel.stateLiveData.observe(viewLifecycleOwner) { it?.let(::handleResult) }

        groupAdapter = GroupieAdapter().apply {
            setOnItemClickListener(listenerStreamItem)
            setOnItemLongClickListener(listenerStreamItem)
        }

        continueWatchingAdapter = GroupieAdapter().apply {
            setOnItemClickListener(listenerStreamItem)
            setOnItemLongClickListener(listenerStreamItem)
        }

        feedBinding.continueWatchingList.apply {
            layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
            adapter = continueWatchingAdapter
        }

        feedBinding.continueWatchingHeader.setOnClickListener {
            NavigationHelper.openStatisticFragment(fm)
        }

        feedBinding.itemsList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.canScrollVertically(-1)) {
                    if (tryGetNewItemsLoadedButton()?.isVisible == true) {
                        hideNewItemsLoaded(true)
                    }
                }
            }
        })

        feedBinding.itemsList.adapter = groupAdapter
        setupListViewMode()
        loadContinueWatchingCarousel()
    }

    private fun loadContinueWatchingCarousel() {
        historyRecordManager?.streamStatistics
            ?.subscribeOn(Schedulers.io())
            ?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribe({ entries ->
                if (_feedBinding == null) return@subscribe
                if (entries.isNullOrEmpty()) {
                    feedBinding.continueWatchingContainer.isVisible = false
                } else {
                    feedBinding.continueWatchingContainer.isVisible = true
                    val carouselItems = entries.take(10).map { entry ->
                        StreamItem(
                            StreamWithState(entry.streamEntity, null)
                        ).apply {
                            itemVersion = StreamItem.ItemVersion.CARD
                        }
                    }
                    continueWatchingAdapter.update(carouselItems)
                }
            }, {
                _feedBinding?.continueWatchingContainer?.isVisible = false
            })?.let { disposables.add(it) }
    }

    override fun onPause() {
        super.onPause()
        listState = feedBinding.itemsList.layoutManager?.onSaveInstanceState()
    }

    override fun onResume() {
        super.onResume()
        updateRelativeTimeViews()
        loadContinueWatchingCarousel()

        if (updateListViewModeOnResume) {
            updateListViewModeOnResume = false
            setupListViewMode()
            if (viewModel.stateLiveData.value != null) {
                handleResult(viewModel.stateLiveData.value!!)
            }
        }

        if (updatePullToRefreshOnResume) {
            updatePullToRefreshOnResume = false
            updatePullToRefreshState()
        }
    }

    private fun setupListViewMode() {
        groupAdapter.spanCount = if (shouldUseGridLayout(context)) getGridSpanCountStreams(context) else 1
        feedBinding.itemsList.layoutManager = GridLayoutManager(requireContext(), groupAdapter.spanCount).apply {
            spanSizeLookup = groupAdapter.spanSizeLookup
        }
    }

    override fun initListeners() {
        super.initListeners()
        feedBinding.refreshRootView.setOnClickListener { reloadContent() }
        feedBinding.swipeRefreshLayout.setOnRefreshListener { reloadContent() }
        feedBinding.newItemsLoadedButton.setOnClickListener {
            hideNewItemsLoaded(true)
            feedBinding.itemsList.scrollToPosition(0)
        }
        setupPlaylistControlListeners()
        updateSwipeRefreshListener()
    }

    private fun updatePullToRefreshState() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        isPullToRefreshEnabled = prefs.getBoolean(
            getString(R.string.pull_to_refresh_key),
            true
        )
        updateSwipeRefreshListener()
        feedBinding.swipeRefreshLayout.isEnabled = isPullToRefreshEnabled
    }

    private fun updateSwipeRefreshListener() {
        feedBinding.swipeRefreshLayout.setOnRefreshListener(null)
        if (isPullToRefreshEnabled) {
            feedBinding.swipeRefreshLayout.setOnRefreshListener { reloadContent() }
        }
    }

    private fun setupPlaylistControlListeners() {
        playlistControlBinding?.let { binding ->
            binding.playlistCtrlPlayAllButton.setOnClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    NavigationHelper.playOnMainPlayer(activity, playQueue)
                }
            }

            binding.playlistCtrlPlayPopupButton.setOnClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    NavigationHelper.playOnPopupPlayer(activity, playQueue, false)
                }
            }

            binding.playlistCtrlPlayBgButton.setOnClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
                    if (prefs.getBoolean(getString(R.string.random_music_play_mode_key), false)) {
                        NavigationHelper.playOnBackgroundPlayerShuffled(activity, playQueue, false)
                    } else {
                        NavigationHelper.playOnBackgroundPlayer(activity, playQueue, false)
                    }
                }
            }

            binding.playlistCtrlPlayPopupButton.setOnLongClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    NavigationHelper.enqueueOnPlayer(activity, playQueue, PlayerService.PlayerType.POPUP)
                }
                true
            }

            binding.playlistCtrlPlayBgButton.setOnLongClickListener {
                val playQueue = getPlayQueue()
                if (playQueue.streams.isNotEmpty()) {
                    NavigationHelper.enqueueOnPlayer(activity, playQueue, PlayerService.PlayerType.AUDIO)
                }
                true
            }
        }
    }

    private fun getPlayQueue(startIndex: Int = 0): PlayQueue {
        val streamInfoItems = mutableListOf<StreamInfoItem>()
        val itemsToUse = if (isFilterEnabled) filteredItems else originalItems

        for (item in itemsToUse) {
            streamInfoItems.add(item.streamWithState.stream.toStreamInfoItem())
        }

        return if (streamInfoItems.isNotEmpty()) {
            SinglePlayQueue(streamInfoItems, startIndex.coerceAtMost(streamInfoItems.size - 1))
        } else {
            SinglePlayQueue(emptyList(), 0)
        }
    }

    private fun Menu.isItemVisible(@IdRes itemId: Int): Boolean =
        findItem(itemId)?.let { it.isVisible } ?: false

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)

        activity?.supportActionBar?.setDisplayShowTitleEnabled(true)
        if (groupName == "") {
            activity?.supportActionBar?.setTitle(R.string.fragment_feed_title)
        } else {
            activity?.supportActionBar?.title = groupName
        }

        inflater.inflate(R.menu.menu_feed_fragment, menu)
        updateTogglePlayedItemsButton(menu.findItem(R.id.menu_item_feed_toggle_played_items))
        menu.findItem(R.id.action_search_feed)?.isVisible =
            menu.isItemVisible(R.id.action_search).not()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_item_feed_toggle_played_items) {
            showPlayedItems = !item.isChecked
            updateTogglePlayedItemsButton(item)
            viewModel.togglePlayedItems(showPlayedItems)
            viewModel.saveShowPlayedItemsToPreferences(showPlayedItems)
        } else if (item.itemId == R.id.menu_item_feed_channel_list) {
            openFeedChannelsFragment(fm, groupId, groupName)
        } else if (item.itemId == R.id.action_search_feed) {
            setupSearchInActionBar()
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onDestroyOptionsMenu() {
        super.onDestroyOptionsMenu()
        activity?.supportActionBar?.subtitle = null
    }

    override fun onDestroy() {
        disposables.dispose()
        if (onSettingsChangeListener != null) {
            PreferenceManager.getDefaultSharedPreferences(activity)
                .unregisterOnSharedPreferenceChangeListener(onSettingsChangeListener)
            onSettingsChangeListener = null
        }

        super.onDestroy()
        activity?.supportActionBar?.subtitle = null
    }

    override fun onDestroyView() {
        if (activity?.supportActionBar?.customView != null) {
            destroyCustomViewInActionBar()
        }
        tryGetNewItemsLoadedButton()?.clearAnimation()

        feedBinding.continueWatchingList.adapter = null
        feedBinding.itemsList.adapter = null
        _feedBinding = null
        playlistControlBinding?.let { binding ->
            binding.playlistCtrlPlayAllButton.setOnClickListener(null)
            binding.playlistCtrlPlayPopupButton.setOnClickListener(null)
            binding.playlistCtrlPlayBgButton.setOnClickListener(null)
            binding.playlistCtrlPlayPopupButton.setOnLongClickListener(null)
            binding.playlistCtrlPlayBgButton.setOnLongClickListener(null)
        }
        playlistControlBinding = null

        originalItems.clear()
        filteredItems.clear()
        editText = null
        searchClear = null

        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        listState?.let { outState.putParcelable("listState", it) }
        outState.putBoolean("showPlayedItems", showPlayedItems)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        listState = savedInstanceState.getParcelable("listState")
        showPlayedItems = savedInstanceState.getBoolean("showPlayedItems", true)
    }

    private fun updateTogglePlayedItemsButton(menuItem: MenuItem) {
        menuItem.isChecked = showPlayedItems
        menuItem.icon = AppCompatResources.getDrawable(
            requireContext(),
            if (showPlayedItems) R.drawable.ic_visibility_on else R.drawable.ic_visibility_off
        )
    }

    private fun setupSearchInActionBar() {
        val actionBar = activity?.supportActionBar ?: return
        val customView = layoutInflater.inflate(R.layout.feed_search_toolbar, null, false)

        actionBar.setCustomView(customView)
        actionBar.setDisplayShowCustomEnabled(true)

        editText = activity?.findViewById(R.id.toolbar_search_edit_text_feed)
        editText?.requestFocus()

        val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)

        try {
            activity?.findViewById<View>(R.id.action_search_feed)?.visibility = View.GONE
        } catch (e: Exception) {
            // ignore
        }

        searchClear = customView.findViewById(R.id.toolbar_search_clear_feed)
        searchClear?.setOnClickListener {
            if (editText?.text?.isEmpty() == true) {
                editText?.clearFocus()
                destroyCustomViewInActionBar()
            } else {
                editText?.setText("")
            }
        }

        try {
            editText?.removeTextChangedListener(textWatcher)
        } catch (e: Exception) {
            // ignore
        }
        editText?.addTextChangedListener(textWatcher)

        setupTouchListeners(feedBinding.root)
    }

    private fun setupTouchListeners(view: View) {
        if (view == editText) {
            return
        }

        if (view !is EditText) {
            view.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN && editText?.hasFocus() == true) {
                    editText?.clearFocus()
                    val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                    imm?.hideSoftInputFromWindow(editText?.windowToken, 0)
                }
                false
            }
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                setupTouchListeners(view.getChildAt(i))
            }
        }

        if (view.id == R.id.items_list) {
            val recyclerView = view as RecyclerView
            recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    if (e.action == MotionEvent.ACTION_DOWN && editText?.hasFocus() == true) {
                        editText?.clearFocus()
                        val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                        imm?.hideSoftInputFromWindow(editText?.windowToken, 0)
                    }
                    return false
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}
                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
            })
        }
    }

    private fun filterItems(text: String) {
        isFilterEnabled = text.isNotEmpty()
        filteredItems.clear()

        if (text.isEmpty()) {
            filteredItems.addAll(originalItems)
        } else {
            for (item in originalItems) {
                val stream = item.streamWithState.stream
                if (stream.title.lowercase().contains(text.lowercase()) ||
                    stream.uploader.lowercase().contains(text.lowercase())) {
                    filteredItems.add(item)
                }
            }
        }

        try {
            groupAdapter.update(if (isFilterEnabled) filteredItems else originalItems)
        } catch (e: Exception) {
            groupAdapter.updateAsync(if (isFilterEnabled) filteredItems else originalItems, null)
        }

        feedBinding.itemsList.post {
            feedBinding.itemsList.layoutManager?.scrollToPosition(0)
        }
    }

    private fun clearFilter() {
        isFilterEnabled = false
        filteredItems.clear()
        try {
            groupAdapter.update(originalItems)
        } catch (e: Exception) {
            groupAdapter.updateAsync(originalItems, null)
        }
        feedBinding.itemsList.post {
            feedBinding.itemsList.layoutManager?.scrollToPosition(0)
        }
    }

    private fun destroyCustomViewInActionBar() {
        val actionBar = activity?.supportActionBar ?: return

        try {
            editText?.removeTextChangedListener(textWatcher)
        } catch (e: Exception) {
            // ignore
        }

        actionBar.setCustomView(null)
        actionBar.setDisplayShowCustomEnabled(false)

        val searchLocal = activity?.findViewById<View>(R.id.action_search_feed)
        searchLocal?.visibility = View.VISIBLE

        clearFilter()

        val imm = activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        activity?.window?.decorView?.windowToken?.let { imm?.hideSoftInputFromWindow(it, 0) }

        editText = null
        searchClear = null
    }

    override fun showLoading() {
        super.showLoading()
        feedBinding.itemsList.animateHideRecyclerViewAllowingScrolling()
        feedBinding.refreshRootView.animate(false, 0)
        feedBinding.loadingProgressText.animate(true, 200)
        feedBinding.swipeRefreshLayout.isRefreshing = true
        isRefreshing = true
    }

    override fun hideLoading() {
        super.hideLoading()
        feedBinding.itemsList.animate(true, 0)
        feedBinding.refreshRootView.animate(true, 200)
        feedBinding.loadingProgressText.animate(false, 0)
        feedBinding.swipeRefreshLayout.isRefreshing = false
        isRefreshing = false
    }

    override fun showEmptyState() {
        super.showEmptyState()
        feedBinding.itemsList.animateHideRecyclerViewAllowingScrolling()
        feedBinding.refreshRootView.animate(true, 200)
        feedBinding.loadingProgressText.animate(false, 0)
        feedBinding.swipeRefreshLayout.isRefreshing = false
        playlistControlBinding?.root?.isVisible = false
    }

    override fun handleResult(result: FeedState) {
        when (result) {
            is FeedState.ProgressState -> handleProgressState(result)
            is FeedState.LoadedState -> handleLoadedState(result)
            is FeedState.ErrorState -> if (handleErrorState(result)) return
        }

        updateRefreshViewState()
    }

    override fun handleError() {
        super.handleError()
        feedBinding.itemsList.animateHideRecyclerViewAllowingScrolling()
        feedBinding.refreshRootView.animate(false, 0)
        feedBinding.loadingProgressText.animate(false, 0)
        feedBinding.swipeRefreshLayout.isRefreshing = false
        isRefreshing = false
    }

    private fun handleProgressState(progressState: FeedState.ProgressState) {
        showLoading()

        val isIndeterminate = progressState.currentProgress == -1 &&
            progressState.maxProgress == -1

        feedBinding.loadingProgressText.text = if (!isIndeterminate) {
            "${progressState.currentProgress}/${progressState.maxProgress}"
        } else if (progressState.progressMessage > 0) {
            getString(progressState.progressMessage)
        } else {
            "∞/∞"
        }

        feedBinding.loadingProgressBar.isIndeterminate = isIndeterminate ||
            (progressState.maxProgress > 0 && progressState.currentProgress == 0)
        feedBinding.loadingProgressBar.progress = progressState.currentProgress
        feedBinding.loadingProgressBar.max = progressState.maxProgress
    }

    private fun showInfoItemDialog(item: StreamInfoItem) {
        val context = context
        val activity: Activity? = activity
        if (context == null || activity == null) return

        InfoItemDialog.Builder(activity, context, this, item).create().show()
    }

    private val listenerStreamItem = object : OnItemClickListener, OnItemLongClickListener {
        override fun onItemClick(item: Item<*>, view: View) {
            if (item is StreamItem && !isRefreshing) {
                val stream = item.streamWithState.stream

                if (autoBackgroundPlaying) {
                    val clickedIndex = groupAdapter.getAdapterPosition(item)
                    val playQueue = getPlayQueue(clickedIndex)

                    if (randomBackgroundPlaying) {
                        playQueue.shuffle()
                    }
                    NavigationHelper.playOnBackgroundPlayer(activity, playQueue, false)
                } else {
                    NavigationHelper.openVideoDetailFragment(
                        requireContext(), fm,
                        stream.serviceId, stream.url, stream.title, null, false
                    )
                }
            }
        }

        override fun onItemLongClick(item: Item<*>, view: View): Boolean {
            if (item is StreamItem && !isRefreshing) {
                showInfoItemDialog(item.streamWithState.stream.toStreamInfoItem())
                return true
            }
            return false
        }
    }

    @SuppressLint("StringFormatMatches")
    private fun handleLoadedState(loadedState: FeedState.LoadedState) {
        val itemVersion = when (getItemViewMode(requireContext())) {
            ItemViewMode.GRID -> StreamItem.ItemVersion.GRID
            ItemViewMode.CARD -> StreamItem.ItemVersion.CARD
            else -> StreamItem.ItemVersion.NORMAL
        }
        loadedState.items.forEach { it.itemVersion = itemVersion }

        originalItems.clear()
        originalItems.addAll(loadedState.items)
        filteredItems.clear()
        filteredItems.addAll(originalItems)

        playlistControlBinding?.root?.isVisible = loadedState.items.isNotEmpty()

        val oldOldestSubscriptionUpdate = oldestSubscriptionUpdate

        groupAdapter.updateAsync(loadedState.items, false) {
            oldOldestSubscriptionUpdate?.run {
                highlightNewItemsAfter(oldOldestSubscriptionUpdate)
            }
        }
        listState?.run {
            feedBinding.itemsList.layoutManager?.onRestoreInstanceState(listState)
            listState = null
        }

        val feedsNotLoaded = loadedState.notLoadedCount > 0
        feedBinding.refreshSubtitleText.isVisible = feedsNotLoaded
        if (feedsNotLoaded) {
            feedBinding.refreshSubtitleText.text = getString(
                R.string.feed_subscription_not_loaded_count,
                loadedState.notLoadedCount
            )
        }

        if (oldestSubscriptionUpdate != loadedState.oldestUpdate ||
            (oldestSubscriptionUpdate == null && loadedState.oldestUpdate == null)
        ) {
            handleItemsErrors(loadedState.itemsErrors)
        }
        oldestSubscriptionUpdate = loadedState.oldestUpdate

        if (loadedState.items.isEmpty()) {
            showEmptyState()
        } else {
            hideLoading()
        }
    }

    private fun handleErrorState(errorState: FeedState.ErrorState): Boolean {
        return if (errorState.error == null) {
            hideLoading()
            false
        } else {
            showError(ErrorInfo(errorState.error, UserAction.REQUESTED_FEED, "Loading feed"))
            true
        }
    }

    private fun handleItemsErrors(errors: List<Throwable>) {
        errors.forEachIndexed { i, t ->
            if (t is FeedLoadService.RequestException &&
                t.cause is ContentNotAvailableException
            ) {
                Single.fromCallable {
                    NewPipeDatabase.getInstance(requireContext()).subscriptionDAO()
                        .getSubscription(t.subscriptionId)
                }.subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(
                        { subscriptionEntity ->
                            handleFeedNotAvailable(
                                subscriptionEntity,
                                t.cause,
                                errors.subList(i + 1, errors.size)
                            )
                        },
                        { throwable -> Log.e(TAG, "Unable to process", throwable) }
                    )
                return
            }
        }
    }

    private fun handleFeedNotAvailable(
        subscriptionEntity: SubscriptionEntity,
        @Nullable cause: Throwable?,
        nextItemsErrors: List<Throwable>
    ) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())
        val isFastFeedModeEnabled = sharedPreferences.getBoolean(
            getString(R.string.feed_use_dedicated_fetch_method_key), false
        )

        val builder = AlertDialog.Builder(requireContext())
            .setTitle(R.string.feed_load_error)
            .setPositiveButton(
                R.string.unsubscribe
            ) { _, _ ->
                SubscriptionManager(requireContext()).deleteSubscription(
                    subscriptionEntity.serviceId, subscriptionEntity.url
                ).subscribe()
                handleItemsErrors(nextItemsErrors)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> }

        var message = getString(R.string.feed_load_error_account_info, subscriptionEntity.name)
        if (cause is AccountTerminatedException) {
            message += "\n" + getString(R.string.feed_load_error_terminated_new)
        } else if (cause is ContentNotAvailableException) {
            if (isFastFeedModeEnabled) {
                message += "\n" + getString(R.string.feed_load_error_fast_unknown)
                builder.setNeutralButton(R.string.feed_use_dedicated_fetch_method_disable_button) { _, _ ->
                    sharedPreferences.edit {
                        putBoolean(getString(R.string.feed_use_dedicated_fetch_method_key), false)
                    }
                }
            } else if (!isNullOrEmpty(cause.message)) {
                message += "\n" + cause.message
            }
        }
        builder.setMessage(message).create().show()
    }

    private fun updateRelativeTimeViews() {
        updateRefreshViewState()
        groupAdapter.notifyItemRangeChanged(
            0, groupAdapter.itemCount,
            StreamItem.UPDATE_RELATIVE_TIME
        )
    }

    private fun updateRefreshViewState() {
        feedBinding.refreshText.text = getString(
            R.string.feed_oldest_subscription_update,
            oldestSubscriptionUpdate?.let { Localization.relativeTime(it) } ?: "—"
        )
    }

    private fun highlightNewItemsAfter(updateTime: OffsetDateTime) {
        var highlightCount = 0
        var doCheck = true

        for (i in 0 until groupAdapter.itemCount) {
            val item = groupAdapter.getItem(i) as StreamItem

            var typeface = Typeface.DEFAULT
            var backgroundSupplier = { ctx: Context ->
                resolveDrawable(ctx, android.R.attr.selectableItemBackground)
            }
            if (doCheck) {
                if (item.streamWithState.stream.uploadDate?.isAfter(updateTime) != false) {
                    highlightCount++

                    typeface = Typeface.DEFAULT_BOLD
                    backgroundSupplier = { ctx: Context ->
                        LayerDrawable(
                            arrayOf(
                                resolveDrawable(ctx, R.attr.dashed_border),
                                resolveDrawable(ctx, android.R.attr.selectableItemBackground)
                            )
                        )
                    }
                } else {
                    doCheck = false
                }
            }

            item.execBindEnd = Consumer { viewBinding ->
                val context = viewBinding.itemRoot.context
                viewBinding.itemRoot.background = backgroundSupplier.invoke(context)
                viewBinding.itemVideoTitleView.typeface = typeface
            }
        }

        groupAdapter.notifyItemRangeChanged(
            0,
            minOf(groupAdapter.itemCount, maxOf(highlightCount, lastNewItemsCount))
        )

        if (highlightCount > 0) {
            showNewItemsLoaded()
        }

        lastNewItemsCount = highlightCount
    }

    private fun showNewItemsLoaded() {
        tryGetNewItemsLoadedButton()?.clearAnimation()
        tryGetNewItemsLoadedButton()
            ?.slideUp(
                250L,
                delay = 100,
                execOnEnd = {
                    try {
                        if (DeviceUtils.hasAnimationsAnimatorDurationEnabled(context)) {
                            hideNewItemsLoaded(true, 10000)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            )
    }

    private fun hideNewItemsLoaded(animate: Boolean, delay: Long = 0) {
        tryGetNewItemsLoadedButton()?.clearAnimation()
        if (animate) {
            tryGetNewItemsLoadedButton()?.animate(
                false,
                200,
                delay = delay,
                execOnEnd = {
                    tryGetNewItemsLoadedButton()?.isVisible = false
                }
            )
        } else {
            tryGetNewItemsLoadedButton()?.isVisible = false
        }
    }

    private fun tryGetNewItemsLoadedButton(): Button? {
        return _feedBinding?.newItemsLoadedButton
    }

    override fun doInitialLoadLogic() {}

    override fun reloadContent() {
        hideNewItemsLoaded(false)

        activity?.startService(
            Intent(requireContext(), FeedLoadService::class.java).apply {
                putExtra(FeedLoadService.EXTRA_GROUP_ID, groupId)
            }
        )
        listState = null
        loadContinueWatchingCarousel()
    }

    companion object {
        const val KEY_GROUP_ID = "ARG_GROUP_ID"
        const val KEY_GROUP_NAME = "ARG_GROUP_NAME"

        @JvmStatic
        fun newInstance(groupId: Long = FeedGroupEntity.GROUP_ALL_ID, groupName: String? = null): FeedFragment {
            val feedFragment = FeedFragment()
            feedFragment.arguments = bundleOf(KEY_GROUP_ID to groupId, KEY_GROUP_NAME to groupName)
            return feedFragment
        }
    }
}
