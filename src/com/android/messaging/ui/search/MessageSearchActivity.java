/*
 * Copyright (C) 2026 The GrapheneOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.android.messaging.ui.search;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.messaging.R;
import com.android.messaging.ui.BugleActionBarActivity;
import com.android.messaging.ui.UIIntents;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Toolbar-driven message search. Live-as-you-type with a 250ms debounce and a 2-character
 * minimum, results are full-text matches against the FTS5 index added in db v3.
 */
public class MessageSearchActivity extends BugleActionBarActivity {
    private static final long DEBOUNCE_MS = 250L;

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Executor mSearchExecutor = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "MessageSearch");
        t.setDaemon(true);
        return t;
    });

    private MessageSearchAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private TextView mEmptyState;

    /** Monotonic counter to discard results from queries the user has already moved past. */
    private long mQueryEpoch;
    private Runnable mPendingSearch;
    private String mLastQuery = "";

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.message_search_activity);
        setTitle(R.string.search_activity_title);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        mRecyclerView = findViewById(R.id.search_results);
        mEmptyState = findViewById(R.id.search_empty_state);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        mAdapter = new MessageSearchAdapter(this, this::onResultClicked);
        mRecyclerView.setAdapter(mAdapter);

        showEmptyState(R.string.search_prompt);
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.message_search_menu, menu);
        final MenuItem searchItem = menu.findItem(R.id.action_search);
        if (searchItem == null) {
            return super.onCreateOptionsMenu(menu);
        }
        final SearchView searchView = (SearchView) searchItem.getActionView();
        if (searchView != null) {
            searchView.setQueryHint(getString(R.string.search_hint));
            searchView.setIconifiedByDefault(false);
            searchItem.expandActionView();
            searchView.requestFocus();
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override
                public boolean onQueryTextSubmit(final String query) {
                    scheduleSearch(query, /*immediate=*/ true);
                    return true;
                }

                @Override
                public boolean onQueryTextChange(final String newText) {
                    scheduleSearch(newText, /*immediate=*/ false);
                    return true;
                }
            });
            // Collapsing the action view exits the activity — there's nothing to fall back to.
            searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override
                public boolean onMenuItemActionExpand(final MenuItem item) {
                    return true;
                }

                @Override
                public boolean onMenuItemActionCollapse(final MenuItem item) {
                    finish();
                    return false;
                }
            });
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        if (mPendingSearch != null) {
            mMainHandler.removeCallbacks(mPendingSearch);
            mPendingSearch = null;
        }
        super.onDestroy();
    }

    private void scheduleSearch(final String query, final boolean immediate) {
        final String normalized = query == null ? "" : query;
        if (normalized.equals(mLastQuery) && !immediate) {
            return;
        }
        mLastQuery = normalized;
        if (mPendingSearch != null) {
            mMainHandler.removeCallbacks(mPendingSearch);
            mPendingSearch = null;
        }
        if (TextUtils.isEmpty(normalized)) {
            mAdapter.setResults(null);
            showEmptyState(R.string.search_prompt);
            return;
        }
        final long epoch = ++mQueryEpoch;
        mPendingSearch = () -> mSearchExecutor.execute(() -> runSearch(normalized, epoch));
        mMainHandler.postDelayed(mPendingSearch, immediate ? 0L : DEBOUNCE_MS);
    }

    private void runSearch(final String query, final long epoch) {
        final List<MessageSearchResult> results;
        try {
            results = MessageSearchQuery.run(query);
        } catch (final RuntimeException e) {
            mMainHandler.post(() -> {
                if (epoch != mQueryEpoch) {
                    return;
                }
                mAdapter.setResults(null);
                showEmptyState(R.string.search_no_results);
            });
            return;
        }
        mMainHandler.post(() -> {
            if (epoch != mQueryEpoch) {
                // The user has typed more since this query was scheduled — drop the result.
                return;
            }
            if (results.isEmpty()) {
                mAdapter.setResults(null);
                showEmptyState(R.string.search_no_results);
            } else {
                mAdapter.setResults(results);
                mEmptyState.setVisibility(View.GONE);
                mRecyclerView.setVisibility(View.VISIBLE);
            }
        });
    }

    private void showEmptyState(final int messageRes) {
        mEmptyState.setText(messageRes);
        mEmptyState.setVisibility(View.VISIBLE);
        mRecyclerView.setVisibility(View.GONE);
    }

    private void onResultClicked(final MessageSearchResult result) {
        UIIntents.get().launchConversationActivity(this, result.conversationId, null);
    }
}
