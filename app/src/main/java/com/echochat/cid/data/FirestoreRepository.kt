package com.echochat.cid.data

import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await

data class RemoteUser(
    val uid: String,
    val displayName: String,
    val avatarBase64: String?
)

data class RemoteMessage(
    val remoteId: String,
    val senderUid: String,
    val content: String,
    val timestampMillis: Long
)

data class RemoteGroup(
    val groupId: String,
    val name: String,
    val ownerUid: String,
    val admins: List<String>,
    val members: List<String>,
    val avatarBase64: String? = null
)

/**
 * Lapisan sinkron lewat Cloud Firestore. Tidak pakai Firebase Auth sama sekali —
 * UID akun tamu dipakai langsung sebagai document id, jadi tidak butuh SHA-1/login apa pun.
 */
class FirestoreRepository {

    private val db = FirebaseFirestore.getInstance()
    private val usersRef = db.collection("users")
    private val chatsRef = db.collection("chats")
    private val groupsRef = db.collection("groups")

    // ---------- Presence & profil ----------

    fun registerPresence(uid: String, displayName: String, avatarBase64: String?) {
        val data = hashMapOf<String, Any>(
            "displayName" to displayName,
            "lastSeen" to System.currentTimeMillis()
        )
        if (avatarBase64 != null) data["avatarBase64"] = avatarBase64
        usersRef.document(uid).set(data, SetOptions.merge())
    }

    suspend fun uidExists(uid: String): Boolean {
        val snapshot = usersRef.document(uid).get().await()
        return snapshot.exists()
    }

    suspend fun fetchUser(uid: String): RemoteUser? {
        val snapshot = usersRef.document(uid).get().await()
        if (!snapshot.exists()) return null
        return RemoteUser(
            uid = uid,
            displayName = snapshot.getString("displayName") ?: "",
            avatarBase64 = snapshot.getString("avatarBase64")
        )
    }

    // ---------- Chat 1-on-1 ----------

    fun chatIdFor(uidA: String, uidB: String): String {
        return listOf(uidA, uidB).sorted().joinToString("_")
    }

    fun sendMessage(chatId: String, senderUid: String, receiverUid: String, content: String): String {
        val docRef = chatsRef.document(chatId).collection("messages").document()
        val data = hashMapOf(
            "senderUid" to senderUid,
            "content" to content,
            "timestamp" to System.currentTimeMillis(),
            "participants" to listOf(senderUid, receiverUid)
        )
        docRef.set(data)
        return docRef.id
    }

    fun listenMessages(chatId: String, onNewMessage: (RemoteMessage) -> Unit): ListenerRegistration {
        return chatsRef.document(chatId).collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    onNewMessage(
                        RemoteMessage(
                            remoteId = doc.id,
                            senderUid = doc.getString("senderUid") ?: continue,
                            content = doc.getString("content") ?: "",
                            timestampMillis = doc.getLong("timestamp") ?: System.currentTimeMillis()
                        )
                    )
                }
            }
    }

    /**
     * Dengarkan SEMUA pesan (chat 1-on-1) di mana UID ini jadi partisipan, lintas percakapan.
     * Dipakai oleh service latar belakang untuk notifikasi. Butuh composite index di Firestore
     * (collection group "messages" + array-contains "participants" + orderBy "timestamp") —
     * Firestore akan kasih link otomatis untuk bikin index itu di kali pertama query dijalankan.
     */
    fun listenAllIncomingMessages(myUid: String, onNewMessage: (chatId: String, RemoteMessage) -> Unit): ListenerRegistration {
        return db.collectionGroup("messages")
            .whereArrayContains("participants", myUid)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    val chatId = doc.reference.parent.parent?.id ?: continue
                    onNewMessage(
                        chatId,
                        RemoteMessage(
                            remoteId = doc.id,
                            senderUid = doc.getString("senderUid") ?: continue,
                            content = doc.getString("content") ?: "",
                            timestampMillis = doc.getLong("timestamp") ?: System.currentTimeMillis()
                        )
                    )
                }
            }
    }

    // ---------- Grup ----------

    fun createGroup(
        name: String,
        ownerUid: String,
        avatarBase64: String? = null,
        initialMembers: List<String> = emptyList()
    ): String {
        val docRef = groupsRef.document()
        val members = (listOf(ownerUid) + initialMembers).distinct()
        val data = hashMapOf<String, Any>(
            "name" to name,
            "ownerUid" to ownerUid,
            "admins" to listOf(ownerUid),
            "members" to members,
            "createdAt" to System.currentTimeMillis()
        )
        if (avatarBase64 != null) data["avatarBase64"] = avatarBase64
        docRef.set(data)
        return docRef.id
    }

    suspend fun fetchGroup(groupId: String): RemoteGroup? {
        val snapshot = groupsRef.document(groupId).get().await()
        if (!snapshot.exists()) return null
        return snapshot.toRemoteGroup()
    }

    suspend fun addMember(groupId: String, uid: String) {
        groupsRef.document(groupId).update("members", FieldValue.arrayUnion(uid)).await()
    }

    suspend fun removeMember(groupId: String, uid: String) {
        groupsRef.document(groupId).update(
            mapOf(
                "members" to FieldValue.arrayRemove(uid),
                "admins" to FieldValue.arrayRemove(uid)
            )
        ).await()
    }

    suspend fun promoteToAdmin(groupId: String, uid: String) {
        groupsRef.document(groupId).update("admins", FieldValue.arrayUnion(uid)).await()
    }

    suspend fun demoteFromAdmin(groupId: String, uid: String) {
        groupsRef.document(groupId).update("admins", FieldValue.arrayRemove(uid)).await()
    }

    suspend fun updateGroupAvatar(groupId: String, avatarBase64: String?) {
        groupsRef.document(groupId).update("avatarBase64", avatarBase64 ?: FieldValue.delete()).await()
    }

    /** Bubarkan grup: hapus semua pesan grup lalu dokumen grupnya sendiri. */
    suspend fun deleteGroup(groupId: String) {
        val messagesRef = groupsRef.document(groupId).collection("messages")
        val messageDocs = messagesRef.get().await()
        for (doc in messageDocs.documents) {
            doc.reference.delete().await()
        }
        groupsRef.document(groupId).delete().await()
    }

    /**
     * Dengarkan grup mana saja yang user ini jadi anggotanya (dipakai untuk sinkron list grup).
     * [onGroupRemoved] dipanggil kalau grup dibubarkan atau user dikeluarkan, supaya salinan lokal ikut dihapus.
     */
    fun listenMyGroups(
        myUid: String,
        onGroup: (RemoteGroup) -> Unit,
        onGroupRemoved: (groupId: String) -> Unit = {}
    ): ListenerRegistration {
        return groupsRef.whereArrayContains("members", myUid)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    when (change.type) {
                        DocumentChange.Type.REMOVED -> onGroupRemoved(change.document.id)
                        else -> onGroup(change.document.toRemoteGroup())
                    }
                }
            }
    }

    private fun DocumentSnapshot.toRemoteGroup(): RemoteGroup {
        return RemoteGroup(
            groupId = id,
            name = getString("name") ?: "",
            ownerUid = getString("ownerUid") ?: "",
            admins = (get("admins") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            members = (get("members") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            avatarBase64 = getString("avatarBase64")
        )
    }

    fun sendGroupMessage(groupId: String, senderUid: String, content: String): String {
        val docRef = groupsRef.document(groupId).collection("messages").document()
        val data = hashMapOf(
            "senderUid" to senderUid,
            "content" to content,
            "timestamp" to System.currentTimeMillis()
        )
        docRef.set(data)
        return docRef.id
    }

    fun listenGroupMessages(groupId: String, onNewMessage: (RemoteMessage) -> Unit): ListenerRegistration {
        return groupsRef.document(groupId).collection("messages")
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshot, _ ->
                if (snapshot == null) return@addSnapshotListener
                for (change in snapshot.documentChanges) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    val doc = change.document
                    onNewMessage(
                        RemoteMessage(
                            remoteId = doc.id,
                            senderUid = doc.getString("senderUid") ?: continue,
                            content = doc.getString("content") ?: "",
                            timestampMillis = doc.getLong("timestamp") ?: System.currentTimeMillis()
                        )
                    )
                }
            }
    }
}
