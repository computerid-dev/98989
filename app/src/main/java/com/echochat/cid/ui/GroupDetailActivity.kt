package com.echochat.cid.ui

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.echochat.cid.R
import com.echochat.cid.data.AppDatabase
import com.echochat.cid.data.FirestoreRepository
import com.echochat.cid.databinding.ActivityGroupDetailBinding
import com.echochat.cid.util.ImageUtils
import com.echochat.cid.util.SessionManager
import kotlinx.coroutines.launch

class GroupDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGroupDetailBinding
    private lateinit var session: SessionManager
    private lateinit var groupId: String
    private val firestoreRepository = FirestoreRepository()
    private lateinit var adapter: GroupMemberAdapter
    private var iAmAdmin = false
    private var iAmOwner = false
    private var currentAvatarBase64: String? = null

    private val pickAvatar = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) applyNewAvatar(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGroupDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        session = SessionManager(this)
        groupId = intent.getStringExtra(EXTRA_GROUP_ID).orEmpty()
        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME).orEmpty()

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.textGroupName.text = groupName

        binding.buttonAddMember.setOnClickListener { showAddMemberDialog() }
        binding.imageGroupAvatar.setOnClickListener {
            if (iAmAdmin) pickAvatar.launch("image/*")
        }
        binding.buttonDisbandGroup.setOnClickListener { confirmDisbandGroup() }

        refreshMembers()
    }

    /**
     * Ambil ulang data grup dari Firestore. DIBUNGKUS try/catch supaya kalau gagal
     * (izin ditolak, koneksi putus, grup sudah dibubarkan, dll) aplikasi menutup
     * layar ini dengan rapi lewat Toast, bukan crash "Terus Berhenti".
     */
    private fun refreshMembers() {
        lifecycleScope.launch {
            val group = try {
                firestoreRepository.fetchGroup(groupId)
            } catch (error: Exception) {
                null
            }

            if (group == null) {
                Toast.makeText(this@GroupDetailActivity, R.string.group_load_failed, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }

            iAmAdmin = session.myUid in group.admins
            iAmOwner = session.myUid == group.ownerUid
            currentAvatarBase64 = group.avatarBase64

            binding.buttonAddMember.visibility = if (iAmAdmin) View.VISIBLE else View.GONE
            binding.buttonDisbandGroup.visibility = if (iAmOwner) View.VISIBLE else View.GONE
            updateAvatarPreview()

            val friendDao = AppDatabase.getInstance(this@GroupDetailActivity).friendDao()
            val members = group.members.map { uid ->
                val nickname = if (uid == session.myUid) {
                    session.displayName
                } else {
                    friendDao.findByUid(uid)?.nickname ?: uid
                }
                GroupMemberUiModel(
                    uid = uid,
                    displayName = nickname,
                    isAdmin = uid in group.admins,
                    isOwner = uid == group.ownerUid,
                    isMe = uid == session.myUid
                )
            }

            adapter = GroupMemberAdapter(canManage = iAmAdmin) { member, anchor ->
                showMemberActionsMenu(member, anchor)
            }
            binding.recyclerMembers.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@GroupDetailActivity)
            binding.recyclerMembers.adapter = adapter
            adapter.submitList(members)
        }
    }

    private fun updateAvatarPreview() {
        val base64 = currentAvatarBase64
        if (base64 != null) {
            val bitmap = ImageUtils.base64ToBitmap(base64)
            if (bitmap != null) binding.imageGroupAvatar.setImageBitmap(bitmap)
        } else {
            binding.imageGroupAvatar.setImageDrawable(null)
        }
    }

    private fun applyNewAvatar(uri: Uri) {
        val base64 = ImageUtils.uriToCompressedBase64(this, uri)
        if (base64 == null) {
            Toast.makeText(this, R.string.backup_failed, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                firestoreRepository.updateGroupAvatar(groupId, base64)
                currentAvatarBase64 = base64
                updateAvatarPreview()

                val database = AppDatabase.getInstance(this@GroupDetailActivity)
                val existing = database.groupDao().findByGroupId(groupId)
                if (existing != null) {
                    database.groupDao().upsert(existing.copy(avatarBase64 = base64))
                }
            } catch (error: Exception) {
                Toast.makeText(this@GroupDetailActivity, R.string.backup_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showMemberActionsMenu(member: GroupMemberUiModel, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, getString(if (member.isAdmin) R.string.action_remove_admin else R.string.action_make_admin))
        popup.menu.add(0, 2, 1, getString(R.string.action_kick_member))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> lifecycleScope.launch {
                    try {
                        if (member.isAdmin) {
                            firestoreRepository.demoteFromAdmin(groupId, member.uid)
                        } else {
                            firestoreRepository.promoteToAdmin(groupId, member.uid)
                        }
                        refreshMembers()
                    } catch (error: Exception) {
                        Toast.makeText(this@GroupDetailActivity, R.string.add_member_failed, Toast.LENGTH_SHORT).show()
                    }
                }
                2 -> lifecycleScope.launch {
                    try {
                        firestoreRepository.removeMember(groupId, member.uid)
                        refreshMembers()
                    } catch (error: Exception) {
                        Toast.makeText(this@GroupDetailActivity, R.string.add_member_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
            true
        }
        popup.show()
    }

    private fun showAddMemberDialog() {
        val input = EditText(this)
        input.hint = getString(R.string.hint_member_uid)

        AlertDialog.Builder(this)
            .setTitle(R.string.action_add_member)
            .setView(input)
            .setPositiveButton(R.string.action_save) { _, _ ->
                val uid = input.text.toString().trim().uppercase()
                if (uid.isNotEmpty()) addMember(uid)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /** DIBUNGKUS try/catch + await, jadi kalau tulis ke Firestore gagal, user dapat feedback jelas (bukan diam saja). */
    private fun addMember(uid: String) {
        lifecycleScope.launch {
            try {
                val exists = firestoreRepository.uidExists(uid)
                if (!exists) {
                    Toast.makeText(this@GroupDetailActivity, R.string.error_friend_id_not_found, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                firestoreRepository.addMember(groupId, uid)
                Toast.makeText(this@GroupDetailActivity, R.string.add_member_success, Toast.LENGTH_SHORT).show()
                refreshMembers()
            } catch (error: Exception) {
                Toast.makeText(this@GroupDetailActivity, R.string.add_member_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDisbandGroup() {
        AlertDialog.Builder(this)
            .setTitle(R.string.confirm_disband_title)
            .setMessage(R.string.confirm_disband_message)
            .setPositiveButton(R.string.action_yes) { _, _ -> disbandGroup() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun disbandGroup() {
        binding.buttonDisbandGroup.isEnabled = false
        lifecycleScope.launch {
            try {
                firestoreRepository.deleteGroup(groupId)

                val database = AppDatabase.getInstance(this@GroupDetailActivity)
                database.groupMessageDao().deleteAllForGroup(groupId)
                database.groupDao().findByGroupId(groupId)?.let { database.groupDao().delete(it) }

                Toast.makeText(this@GroupDetailActivity, R.string.disband_success, Toast.LENGTH_SHORT).show()
                finish()
            } catch (error: Exception) {
                binding.buttonDisbandGroup.isEnabled = true
                Toast.makeText(this@GroupDetailActivity, R.string.disband_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_GROUP_NAME = "extra_group_name"
    }
}
