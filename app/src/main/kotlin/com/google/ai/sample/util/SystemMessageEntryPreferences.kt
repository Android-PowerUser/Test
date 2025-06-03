package com.google.ai.sample.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer

object SystemMessageEntryPreferences {
    private const val TAG = "SystemMessageEntryPrefs"
    private const val PREFS_NAME = "system_message_entry_prefs"
    private const val KEY_SYSTEM_MESSAGE_ENTRIES = "system_message_entries"

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveEntries(context: Context, entries: List<SystemMessageEntry>) {
        try {
            val jsonString = Json.encodeToString(ListSerializer(SystemMessageEntry.serializer()), entries)
            Log.d(TAG, "Saving ${entries.size} entries. First entry title if exists: ${entries.firstOrNull()?.title}.")
            // Log.v(TAG, "Saving JSON: $jsonString") // Verbose, uncomment if needed for deep debugging
            val editor = getSharedPreferences(context).edit()
            editor.putString(KEY_SYSTEM_MESSAGE_ENTRIES, jsonString)
            editor.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error saving entries: ${e.message}", e)
        }
    }

    fun loadEntries(context: Context): List<SystemMessageEntry> {
        try {
            val jsonString = getSharedPreferences(context).getString(KEY_SYSTEM_MESSAGE_ENTRIES, null)
            if (jsonString != null) {
                // Log.v(TAG, "Loaded JSON: $jsonString") // Verbose
                val loadedEntries = Json.decodeFromString(ListSerializer(SystemMessageEntry.serializer()), jsonString)
                Log.d(TAG, "Loaded ${loadedEntries.size} entries. First entry title if exists: ${loadedEntries.firstOrNull()?.title}.")
                return loadedEntries
            }
            Log.d(TAG, "No entries found, returning empty list.")
            return emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading entries: ${e.message}", e)
            return emptyList()
        }
    }

    fun addEntry(context: Context, entry: SystemMessageEntry) {
        Log.d(TAG, "Adding entry: Title='${entry.title}'")
        val entries = loadEntries(context).toMutableList()
        entries.add(entry)
        saveEntries(context, entries)
    }

    fun updateEntry(context: Context, oldEntry: SystemMessageEntry, newEntry: SystemMessageEntry) {
        Log.d(TAG, "Updating entry: OldTitle='${oldEntry.title}', NewTitle='${newEntry.title}'")
        val entries = loadEntries(context).toMutableList()
        val index = entries.indexOfFirst { it.title == oldEntry.title } 
        if (index != -1) {
            entries[index] = newEntry
            saveEntries(context, entries)
            Log.i(TAG, "Entry updated successfully: NewTitle='${newEntry.title}'")
        } else {
            Log.w(TAG, "Entry with old title '${oldEntry.title}' not found for update.")
            // Optionally, add the new entry if the old one is not found
            // addEntry(context, newEntry)
        }
    }

    fun deleteEntry(context: Context, entryToDelete: SystemMessageEntry) {
        val entries = loadEntries(context).toMutableList()
        // Assuming title is unique for deletion. A more robust approach might use a unique ID.
        val removed = entries.removeAll { it.title == entryToDelete.title && it.guide == entryToDelete.guide }
        if (removed) {
            saveEntries(context, entries)
            Log.d(TAG, "Deleted entry: ${entryToDelete.title}")
        } else {
            Log.w(TAG, "Entry not found for deletion: ${entryToDelete.title}")
        }
    }
}
