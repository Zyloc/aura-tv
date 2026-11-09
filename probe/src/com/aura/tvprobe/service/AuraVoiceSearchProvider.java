package com.aura.tvprobe;

import android.app.SearchManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import org.json.JSONArray;

/**
 * Global Android TV Searchable ContentProvider for Aura AI.
 * Receives live voice search queries from Google Assistant (com.google.android.katniss)
 * when the user presses the physical remote microphone button (KEYCODE_ASSIST),
 * immediately triggers Aura's Agentic Search Studio if Aura was active, and returns
 * global search suggestions.
 */
public class AuraVoiceSearchProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        String query = VoiceSearchHelper.extractProviderQuery(uri, selectionArgs);
        if (query.length() < 2) {
            return new MatrixCursor(VoiceSearchHelper.SUGGEST_COLUMNS);
        }
        Context ctx = getContext();
        if (ctx != null) {
            AuraActivity.onGlobalVoiceSearchQuery(ctx, query);
        }
        JSONArray candidates = JustWatchGraph.fetchDeterministicSearchCandidates(query, 1990);
        return VoiceSearchHelper.buildSuggestionsCursor(query, candidates);
    }

    @Override
    public String getType(Uri uri) {
        return SearchManager.SUGGEST_MIME_TYPE;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
