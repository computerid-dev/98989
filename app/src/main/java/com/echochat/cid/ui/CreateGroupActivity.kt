package com.echochat.cid.ui

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.echochat.cid.R
import com.echochat.cid.data.AppDatabase
import com.echochat.cid.data.Friend
import com.echochat.cid.data.FirestoreRepository
import com.echochat.cid.data.GroupChat
import com.echochat.cid.databinding.ActivityCreateGroupBinding
import com.echochat.cid.util.SessionManager
import kotlinx.coroutines.launch

class CreateGroupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateGroupBinding
    private lateinit var session: SessionManager
    private val firestoreRepository = FirestoreRepository()

    private var friendOptions: List<Friend> = emptyList()
    private val selectedFriendUids = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateGroupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        updateSelectedMembersLabel()
        loadFriendOptions()

        binding.buttonAddMembersSetup.setOnClickListener { showMemberPickerDialog() }
        binding.buttonCreateGroup.setOnClickListener { createGroup() }
    }

    private fun loadFriendOptions() {
        lifecycleScope.launch {
            friendOptions = AppDatabase.getInstance(this@CreateGroupActivity).friendDao().snapshotAll()
        }
    }

    private fun showMemberPickerDialog() {
        if (friendOptions.isEmpty()) {
            updateSelectedMembersLabel()
            return
        }
        val names = friendOptions.map { it.nickname }.toTypedArray()
        val checked = friendOptions.map { it.friendUid in selectedFriendUids }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.pick_members_title)
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val uid = friendOptions[which].friendUid
                if (isChecked) selectedFriendUids.add(uid) else selectedFriendUids.remove(uid)
            }
            .setPositiveButton(R.string.action_save) { _, _ -> updateSelectedMembersLabel() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun updateSelectedMembersLabel() {
        binding.textSelectedMembers.text = if (selectedFriendUids.isEmpty()) {
            getString(R.string.selected_members_none)
        } else {
            getString(R.string.selected_members_count, selectedFriendUids.size)
        }
    }

    private fun createGroup() {
        val name = binding.inputGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.inputGroupName.error = getString(R.string.error_group_name_empty)
            return
        }

        binding.buttonCreateGroup.isEnabled = false
        val groupId = firestoreRepository.createGroup(
            name = name,
            ownerUid = session.myUid,
            initialMembers = selectedFriendUids.toList()
        )

        lifecycleScope.launch {
            AppDatabase.getInstance(this@CreateGroupActivity).groupDao().upsert(
                GroupChat(groupId = groupId, name = name)
            )
            val intent = android.content.Intent(this@CreateGroupActivity, GroupDetailActivity::class.java)
            intent.putExtra(GroupDetailActivity.EXTRA_GROUP_ID, groupId)
            intent.putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, name)
            startActivity(intent)
            finish()
        }
    }
}
