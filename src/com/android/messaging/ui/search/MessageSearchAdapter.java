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

import android.content.Context;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.messaging.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MessageSearchAdapter extends
        RecyclerView.Adapter<MessageSearchAdapter.ViewHolder> {

    public interface OnItemClickListener {
        void onItemClick(MessageSearchResult result);
    }

    private List<MessageSearchResult> mResults = Collections.emptyList();
    private final OnItemClickListener mListener;
    private final int mHighlightColor;

    public MessageSearchAdapter(final Context context, final OnItemClickListener listener) {
        mListener = listener;
        mHighlightColor = context.getResources().getColor(R.color.text_highlight_color);
    }

    public void setResults(final List<MessageSearchResult> results) {
        mResults = results == null ? Collections.<MessageSearchResult>emptyList()
                : new ArrayList<>(results);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType) {
        final View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.message_search_result_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, final int position) {
        final MessageSearchResult r = mResults.get(position);
        holder.name.setText(r.conversationName == null ? "" : r.conversationName);
        holder.snippet.setText(SearchHighlight.toSpannable(r.snippet, mHighlightColor));
        holder.timestamp.setText(DateUtils.getRelativeTimeSpanString(
                r.messageReceivedTimestamp, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE));
        holder.itemView.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onItemClick(r);
            }
        });
    }

    @Override
    public int getItemCount() {
        return mResults.size();
    }

    static final class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView snippet;
        final TextView timestamp;

        ViewHolder(final View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.search_result_name);
            snippet = itemView.findViewById(R.id.search_result_snippet);
            timestamp = itemView.findViewById(R.id.search_result_timestamp);
        }
    }
}
