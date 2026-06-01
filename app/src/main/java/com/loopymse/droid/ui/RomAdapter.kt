package com.loopymse.droid.ui

import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.loopymse.droid.databinding.ItemRomBinding

/**
 * RecyclerView adapter for the ROM library list.
 * Uses ListAdapter + DiffUtil for efficient updates (filter/sort without full redraw).
 */
class RomAdapter(
    private val onPlay: (RomEntry) -> Unit,
    private val onLongPress: (RomEntry) -> Unit
) : ListAdapter<RomEntry, RomAdapter.RomViewHolder>(RomDiffCallback()) {

    inner class RomViewHolder(private val binding: ItemRomBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: RomEntry) {
            // Title: use the display name
            binding.tvRomName.text = entry.displayName

            // Metadata: filename · size · last played
            val lastPlayedLabel = if (entry.lastPlayedMs == 0L) {
                "Never played"
            } else {
                "Last played " + DateUtils.getRelativeTimeSpanString(
                    entry.lastPlayedMs,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS
                ).toString().lowercase()
            }
            binding.tvRomMeta.text = entry.metaString(lastPlayedLabel)

            // Row tap: same as play (for easy thumb access)
            binding.root.setOnClickListener { onPlay(entry) }

            // Play button tap
            binding.btnPlay.setOnClickListener { onPlay(entry) }

            // Long press: context menu (remove, info)
            binding.root.setOnLongClickListener {
                onLongPress(entry)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RomViewHolder {
        val binding = ItemRomBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return RomViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RomViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** Filters the displayed list by a search query. Case-insensitive name match. */
    fun filter(query: String, fullList: List<RomEntry>) {
        val filtered = if (query.isBlank()) {
            fullList
        } else {
            fullList.filter { it.displayName.contains(query.trim(), ignoreCase = true) }
        }
        submitList(filtered)
    }
}

class RomDiffCallback : DiffUtil.ItemCallback<RomEntry>() {
    override fun areItemsTheSame(oldItem: RomEntry, newItem: RomEntry) =
        oldItem.uriString == newItem.uriString

    override fun areContentsTheSame(oldItem: RomEntry, newItem: RomEntry) =
        oldItem == newItem
}
