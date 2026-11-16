package com.babylon.chat.iq;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Group creation and administration screen. */
public class GroupActivity extends Activity {

    private static final int PICK_GROUP_PHOTO = 701;
    private static final int MAX_MEMBERS = 100;
    private static final int GOLD = 0xFFD8B34A;

    private FirebaseFirestore db;
    private FirebaseUser user;
    private String uid;
    private String groupId;
    private String ownerId;
    private boolean managing;
    private boolean admin;
    private boolean owner;
    private boolean loading;
    private String photoData = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Map<String, Object>> candidates = new LinkedHashMap<>();
    private final LinkedHashMap<String, Map<String, Object>> selected = new LinkedHashMap<>();
    private final ArrayList<String> participants = new ArrayList<>();
    private final ArrayList<String> admins = new ArrayList<>();

    private LinearLayout root;
    private LinearLayout content;
    private LinearLayout peopleList;
    private LinearLayout membersList;
    private EditText nameInput;
    private EditText descriptionInput;
    private EditText searchInput;
    private FrameLayout photoFrame;
    private ImageView photoImage;
    private TextView photoGlyph;
    private TextView countLabel;
    private TextView primaryButton;
    private Runnable searchTask;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        db = FirebaseFirestore.getInstance();
        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        uid = user.getUid();
        managing = getIntent().getBooleanExtra("manage_group", false);
        groupId = getIntent().getStringExtra("group_id");
        if (managing && (groupId == null || groupId.isEmpty())) {
            finish();
            return;
        }
        buildScreen();
        if (managing) loadGroup();
    }

    private void buildScreen() {
        boolean dark = AuthTheme.isDark(this);
        final int card = dark ? 0xFF172432 : 0xFFFFFFFF;
        final int ink = dark ? 0xFFF2F4F7 : 0xFF1A2734;
        final int muted = dark ? 0xFF9BA9B7 : 0xFF728092;

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                dark ? new int[]{0xFF101A26, 0xFF0B121B} : new int[]{0xFFFFFCF4, 0xFFF0E8D8}));
        setContentView(root);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(16), dp(12), dp(16), dp(8));
        TextView back = text("‹", 34, ink, true);
        back.setGravity(Gravity.CENTER);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(42), dp(44)));
        back.setOnClickListener(v -> finish());
        TextView title = text(managing ? "إعدادات المجموعة" : "مجموعة جديدة", 20, ink, true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.setMarginEnd(dp(8));
        toolbar.addView(title, titleLp);
        root.addView(toolbar);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(16), dp(8), dp(16), dp(20));
        scroll.setFillViewport(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout profile = card(card, dp(22));
        profile.setGravity(Gravity.CENTER_VERTICAL);
        profile.setPadding(dp(16), dp(16), dp(16), dp(16));
        content.addView(profile, bottomMargin(-1, -2, 14));

        photoFrame = new FrameLayout(this);
        photoFrame.setBackground(oval(dark ? 0xFF29394A : 0xFFF0E6D1));
        photoFrame.setClipToOutline(true);
        photoImage = new ImageView(this);
        photoImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photoImage.setVisibility(View.GONE);
        photoFrame.addView(photoImage, new FrameLayout.LayoutParams(-1, -1));
        photoGlyph = text("＋", 27, dark ? 0xFFFFFFFF : 0xFF344354, true);
        photoGlyph.setGravity(Gravity.CENTER);
        photoFrame.addView(photoGlyph, new FrameLayout.LayoutParams(-1, -1));
        photoFrame.setOnClickListener(v -> pickPhoto());
        profile.addView(photoFrame, new LinearLayout.LayoutParams(dp(72), dp(72)));
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(0, -2, 1f);
        fieldLp.setMarginEnd(dp(14));
        profile.addView(fields, fieldLp);

        nameInput = input("اسم المجموعة", 40, ink, muted, card);
        nameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(48)});
        nameInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { setCreateCount(); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        fields.addView(nameInput, bottomMargin(-1, dp(48), 9));
        descriptionInput = input("وصف مختصر (اختياري)", 15, ink, muted, card);
        descriptionInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(160)});
        fields.addView(descriptionInput, new LinearLayout.LayoutParams(-1, dp(46)));

        TextView section = text(managing ? "الأعضاء" : "إضافة أعضاء", 17, ink, true);
        section.setPadding(dp(4), dp(8), dp(4), dp(8));
        content.addView(section);

        countLabel = text("", 13, muted, false);
        countLabel.setPadding(dp(4), 0, dp(4), dp(8));
        content.addView(countLabel);

        LinearLayout searchCard = card(card, dp(16));
        searchCard.setPadding(dp(12), dp(2), dp(12), dp(2));
        searchCard.setTag("group_search_card");
        content.addView(searchCard, bottomMargin(-1, -2, 10));
        searchInput = input("ابحث باسم المستخدم", 15, ink, muted, card);
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        searchCard.addView(searchInput, new LinearLayout.LayoutParams(-1, dp(48)));
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { scheduleSearch(s.toString()); }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });

        peopleList = new LinearLayout(this);
        peopleList.setOrientation(LinearLayout.VERTICAL);
        content.addView(peopleList);
        membersList = new LinearLayout(this);
        membersList.setOrientation(LinearLayout.VERTICAL);
        content.addView(membersList);

        if (managing) {
            addManageActions(ink, muted, card);
            loadUsers("");
        } else {
            loadUsers("");
        }

        primaryButton = text(managing ? "حفظ التعديلات" : "إنشاء المجموعة", 16, 0xFF17212B, true);
        primaryButton.setGravity(Gravity.CENTER);
        primaryButton.setBackground(round(GOLD, dp(17)));
        primaryButton.setElevation(dp(3));
        LinearLayout.LayoutParams primaryLp = new LinearLayout.LayoutParams(-1, dp(54));
        primaryLp.setMargins(dp(16), dp(8), dp(16), dp(14));
        root.addView(primaryButton, primaryLp);
        primaryButton.setOnClickListener(v -> {
            if (managing && !selected.isEmpty()) addSelectedMembers();
            else if (managing) saveGroup();
            else createGroup();
        });
        setCreateCount();
    }

    private void addManageActions(int ink, int muted, int cardColor) {
        LinearLayout actions = card(cardColor, dp(20));
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(dp(14), dp(8), dp(14), dp(8));
        content.addView(actions, bottomMargin(-1, -2, 14));
        TextView invite = text("رابط الدعوة", 15, ink, true);
        invite.setPadding(dp(4), dp(12), dp(4), dp(12));
        actions.addView(invite);
        invite.setOnClickListener(v -> shareInvite());
        TextView rotate = text("إنشاء رابط جديد وإلغاء القديم", 14, muted, false);
        rotate.setPadding(dp(4), dp(10), dp(4), dp(10));
        actions.addView(rotate);
        rotate.setOnClickListener(v -> rotateInvite());
        TextView leave = text("مغادرة المجموعة", 14, 0xFFE36A6A, true);
        leave.setPadding(dp(4), dp(12), dp(4), dp(12));
        content.addView(leave);
        leave.setOnClickListener(v -> leaveGroup());

        TextView delete = text("حذف المجموعة نهائياً", 14, 0xFFE36A6A, true);
        delete.setPadding(dp(4), dp(10), dp(4), dp(10));
        content.addView(delete);
        delete.setOnClickListener(v -> deleteGroup());
        delete.setVisibility(View.GONE);
        delete.setTag("delete_group");
    }

    private void loadGroup() {
        loading = true;
        db.collection("chats").document(groupId).get()
                .addOnSuccessListener(this::applyGroup)
                .addOnFailureListener(e -> finishWithError("تعذّر تحميل المجموعة"));
    }

    @SuppressWarnings("unchecked")
    private void applyGroup(DocumentSnapshot snapshot) {
        if (!snapshot.exists() || !"group".equals(snapshot.getString("type"))) {
            finishWithError("المجموعة غير موجودة");
            return;
        }
        Object rawMembers = snapshot.get("participants");
        participants.clear();
        if (rawMembers instanceof List) {
            for (Object member : (List<?>) rawMembers) participants.add(String.valueOf(member));
        }
        if (!participants.contains(uid)) {
            finishWithError("لم تعد عضواً في هذه المجموعة");
            return;
        }
        Object rawAdmins = snapshot.get("admins");
        admins.clear();
        if (rawAdmins instanceof List) for (Object adminId : (List<?>) rawAdmins) admins.add(String.valueOf(adminId));
        ownerId = snapshot.getString("ownerId");
        owner = uid.equals(ownerId);
        admin = owner || admins.contains(uid);

        loadUsers(searchInput.getText().toString().trim());
        nameInput.setText(snapshot.getString("name") == null ? "" : snapshot.getString("name"));
        descriptionInput.setText(snapshot.getString("description") == null ? "" : snapshot.getString("description"));
        photoData = snapshot.getString("photoData") == null ? "" : snapshot.getString("photoData");
        showPhoto();
        nameInput.setEnabled(admin);
        descriptionInput.setEnabled(admin);
        photoFrame.setEnabled(admin);
        primaryButton.setEnabled(admin);
        primaryButton.setAlpha(admin ? 1f : .45f);
        View delete = content.findViewWithTag("delete_group");
        if (delete != null) delete.setVisibility(owner ? View.VISIBLE : View.GONE);
        View searchCard = content.findViewWithTag("group_search_card");
        if (searchCard != null) searchCard.setVisibility(admin ? View.VISIBLE : View.GONE);
        peopleList.setVisibility(admin ? View.VISIBLE : View.GONE);
        TextView count = countLabel;
        if (count != null) count.setText(participants.size() + " من " + MAX_MEMBERS + " عضواً");
        renderMembers();
        renderPeople();
        setCreateCount();
        loading = false;
    }

    private void renderMembers() {
        membersList.removeAllViews();
        final boolean dark = AuthTheme.isDark(this);
        final int cardColor = dark ? 0xFF172432 : 0xFFFFFFFF;
        for (String memberId : participants) {
            final String fuid = memberId;
            db.collection("users").document(memberId).get()
                    .addOnSuccessListener(doc -> {
                        if (isFinishing() || !participants.contains(fuid)) return;
                        String name = doc.getString("name");
                        if (name == null || name.trim().isEmpty()) name = "عضو " + fuid.substring(0, Math.min(5, fuid.length()));
                        String role = fuid.equals(ownerId) ? "مالك المجموعة" : admins.contains(fuid) ? "مشرف" : "عضو";
                        LinearLayout row = card(cardColor, dp(16));
                        row.setGravity(Gravity.CENTER_VERTICAL);
                        row.setPadding(dp(12), dp(8), dp(12), dp(8));
                        LinearLayout.LayoutParams rowLp = bottomMargin(-1, -2, 7);
                        membersList.addView(row, rowLp);
                        TextView avatar = text(name.substring(0, 1).toUpperCase(Locale.ROOT), 17, 0xFF182533, true);
                        avatar.setGravity(Gravity.CENTER);
                        avatar.setBackground(oval(dark ? 0xFFD8B34A : 0xFFE5C76F));
                        row.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
                        LinearLayout names = new LinearLayout(this);
                        names.setOrientation(LinearLayout.VERTICAL);
                        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
                        nlp.setMarginEnd(dp(12));
                        row.addView(names, nlp);
                        TextView memberName = text(name, 14, dark ? 0xFFF2F4F7 : 0xFF1A2734, true);
                        TextView memberRole = text(role, 12, dark ? 0xFF9BA9B7 : 0xFF728092, false);
                        names.addView(memberName);
                        names.addView(memberRole);
                        if (owner && !uid.equals(fuid)) {
                            TextView roleAction = actionText(admins.contains(fuid) ? "إزالة الإشراف" : "ترقية", dark);
                            row.addView(roleAction);
                            roleAction.setOnClickListener(v -> changeAdmin(fuid));
                            TextView transfer = actionText("نقل الملكية", dark);
                            row.addView(transfer);
                            transfer.setOnClickListener(v -> transferOwnership(fuid));
                        }
                        if (admin && !uid.equals(fuid) && !fuid.equals(ownerId) && (owner || !admins.contains(fuid))) {
                            TextView remove = actionText("إزالة", dark);
                            row.addView(remove);
                            remove.setOnClickListener(v -> removeMember(fuid));
                        }
                    });
        }
    }

    private void scheduleSearch(String query) {
        if (searchTask != null) handler.removeCallbacks(searchTask);
        searchTask = () -> loadUsers(query.trim());
        handler.postDelayed(searchTask, 260);
    }

    private void loadUsers(String query) {
        String prefix = query.trim().replaceFirst("^@", "").toLowerCase(Locale.ROOT);
        Query users = db.collection("users").orderBy("username").limit(query.isEmpty() ? 24 : 30);
        if (!prefix.isEmpty()) users = users.startAt(prefix).endAt(prefix + "\uf8ff");
        users.get().addOnSuccessListener(snapshots -> {
            candidates.clear();
            for (DocumentSnapshot doc : snapshots.getDocuments()) {
                if (doc.getId().equals(uid) || (managing && participants.contains(doc.getId()))) continue;
                Map<String, Object> profile = doc.getData();
                if (profile != null) candidates.put(doc.getId(), profile);
            }
            renderPeople();
        }).addOnFailureListener(e -> {
            if (!isFinishing()) toast("تعذّر البحث عن الأعضاء");
        });
    }

    private void renderPeople() {
        peopleList.removeAllViews();
        if (managing && !admin) return;
        final boolean dark = AuthTheme.isDark(this);
        final int cardColor = dark ? 0xFF172432 : 0xFFFFFFFF;
        for (Map.Entry<String, Map<String, Object>> entry : candidates.entrySet()) {
            final String memberId = entry.getKey();
            final Map<String, Object> profile = entry.getValue();
            String name = profile.get("name") instanceof String ? (String) profile.get("name") : "مستخدم";
            String username = profile.get("username") instanceof String ? (String) profile.get("username") : "";
            LinearLayout row = card(cardColor, dp(16));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            LinearLayout.LayoutParams rowLp = bottomMargin(-1, -2, 7);
            peopleList.addView(row, rowLp);
            TextView avatar = text(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT), 17, 0xFF182533, true);
            avatar.setGravity(Gravity.CENTER);
            avatar.setBackground(oval(dark ? 0xFF34506A : 0xFFEADDBF));
            row.addView(avatar, new LinearLayout.LayoutParams(dp(42), dp(42)));
            LinearLayout names = new LinearLayout(this);
            names.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0, -2, 1f);
            nlp.setMarginEnd(dp(12));
            row.addView(names, nlp);
            names.addView(text(name, 14, dark ? 0xFFF2F4F7 : 0xFF1A2734, true));
            names.addView(text(username.startsWith("@") ? username : "@" + username, 12,
                    dark ? 0xFF9BA9B7 : 0xFF728092, false));
            boolean checked = selected.containsKey(memberId);
            TextView check = text(checked ? "✓" : "+", 20, checked ? 0xFF17212B : GOLD, true);
            check.setGravity(Gravity.CENTER);
            check.setBackground(oval(checked ? GOLD : (dark ? 0xFF263747 : 0xFFF5EFDF)));
            row.addView(check, new LinearLayout.LayoutParams(dp(36), dp(36)));
            row.setOnClickListener(v -> toggleMember(memberId, profile));
        }
        setCreateCount();
    }

    private void toggleMember(String memberId, Map<String, Object> profile) {
        if (selected.containsKey(memberId)) {
            selected.remove(memberId);
        } else {
            int current = managing ? participants.size() + selected.size() : selected.size() + 1;
            if (current >= MAX_MEMBERS) {
                toast("الحد الأقصى للمجموعة " + MAX_MEMBERS + " عضو");
                return;
            }
            selected.put(memberId, profile);
        }
        renderPeople();
    }

    private void setCreateCount() {
        if (countLabel == null) return;
        int count = managing ? participants.size() + selected.size() : selected.size() + 1;
        countLabel.setText(managing
                ? participants.size() + " من " + MAX_MEMBERS + " عضواً"
                : count + " من " + MAX_MEMBERS + " أعضاء");
        if (primaryButton != null && managing) {
            primaryButton.setText(selected.isEmpty() ? "حفظ التعديلات" : "إضافة الأعضاء المحددين");
            primaryButton.setEnabled(admin);
            primaryButton.setAlpha(admin ? 1f : .45f);
        } else if (primaryButton != null) {
            boolean ready = nameInput != null && nameInput.getText().toString().trim().length() >= 2 && !selected.isEmpty();
            primaryButton.setEnabled(ready);
            primaryButton.setAlpha(ready ? 1f : .55f);
        }
    }

    private void createGroup() {
        String name = nameInput.getText().toString().trim();
        if (name.length() < 2) {
            nameInput.setError("اكتب اسماً للمجموعة");
            return;
        }
        if (selected.isEmpty()) {
            toast("اختر عضواً واحداً على الأقل");
            return;
        }
        if (selected.size() + 1 > MAX_MEMBERS) {
            toast("الحد الأقصى للمجموعة " + MAX_MEMBERS + " عضو");
            return;
        }
        primaryButton.setEnabled(false);
        primaryButton.setText("جارٍ إنشاء المجموعة…");
        final String code = newInviteCode();
        final long now = System.currentTimeMillis();
        final DocumentReference groupRef = db.collection("chats").document();
        final DocumentReference inviteRef = db.collection("groupInvites").document(code);
        ArrayList<String> memberIds = new ArrayList<>();
        memberIds.add(uid);
        memberIds.addAll(selected.keySet());
        HashMap<String, Object> unread = new HashMap<>();
        for (String memberId : memberIds) unread.put(memberId, 0L);

        Map<String, Object> group = new HashMap<>();
        group.put("type", "group");
        group.put("name", name);
        group.put("description", descriptionInput.getText().toString().trim());
        group.put("photoData", photoData);
        group.put("ownerId", uid);
        group.put("admins", Arrays.asList(uid));
        group.put("participants", memberIds);
        group.put("memberLimit", MAX_MEMBERS);
        group.put("inviteCode", code);
        group.put("unreadCounts", unread);
        group.put("createdAt", now);
        group.put("updatedAt", now);
        group.put("lastMessage", "تم إنشاء المجموعة");
        group.put("lastMessageAt", now);
        group.put("lastSender", uid);
        Map<String, Object> invite = new HashMap<>();
        invite.put("groupId", groupRef.getId());
        invite.put("active", true);
        invite.put("createdBy", uid);
        invite.put("createdAt", now);
        db.batch().set(groupRef, group).set(inviteRef, invite).commit()
                .addOnSuccessListener(v -> {
                    Intent result = new Intent().putExtra("group_id", groupRef.getId());
                    setResult(RESULT_OK, result);
                    toast("تم إنشاء المجموعة");
                    finish();
                })
                .addOnFailureListener(e -> {
                    primaryButton.setEnabled(true);
                    primaryButton.setText("إنشاء المجموعة");
                    toast("تعذّر إنشاء المجموعة، حاول مرة أخرى");
                });
    }

    private void saveGroup() {
        if (!admin || loading) return;
        String name = nameInput.getText().toString().trim();
        if (name.length() < 2) {
            nameInput.setError("اكتب اسماً للمجموعة");
            return;
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put("name", name);
        updates.put("description", descriptionInput.getText().toString().trim());
        updates.put("photoData", photoData);
        updates.put("updatedAt", System.currentTimeMillis());
        primaryButton.setEnabled(false);
        db.collection("chats").document(groupId).update(updates)
                .addOnSuccessListener(v -> {
                    primaryButton.setEnabled(true);
                    toast("تم تحديث معلومات المجموعة");
                    setResult(RESULT_OK);
                })
                .addOnFailureListener(e -> {
                    primaryButton.setEnabled(true);
                    toast("تعذّر حفظ التعديلات");
                });
    }

    private void addSelectedMembers() {
        if (!admin || selected.isEmpty()) return;
        final ArrayList<String> additions = new ArrayList<>(selected.keySet());
        primaryButton.setEnabled(false);
        DocumentReference ref = db.collection("chats").document(groupId);
        db.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(ref);
            List<?> existing = (List<?>) snapshot.get("participants");
            ArrayList<String> next = new ArrayList<>();
            if (existing != null) for (Object member : existing) next.add(String.valueOf(member));
            for (String addition : additions) if (!next.contains(addition)) next.add(addition);
            if (next.size() > MAX_MEMBERS) throw new IllegalStateException("MEMBER_LIMIT");
            transaction.update(ref, "participants", next);
            return null;
        }).addOnSuccessListener(v -> {
            selected.clear();
            loadGroup();
            loadUsers(searchInput.getText().toString().trim());
            toast("تمت إضافة الأعضاء");
        }).addOnFailureListener(e -> {
            primaryButton.setEnabled(admin);
            toast("تعذّرت الإضافة؛ تأكد من عدد الأعضاء وصلاحياتك");
        });
    }

    private void changeAdmin(String memberId) {
        if (!owner) return;
        boolean promote = !admins.contains(memberId);
        new AlertDialog.Builder(this)
                .setTitle(promote ? "ترقية إلى مشرف" : "إزالة الإشراف")
                .setMessage(promote ? "سيتمكن هذا العضو من إدارة المجموعة." : "سيفقد هذا العضو صلاحيات الإشراف.")
                .setPositiveButton("تأكيد", (d, w) -> {
                    FieldValue change = promote ? FieldValue.arrayUnion(memberId) : FieldValue.arrayRemove(memberId);
                    db.collection("chats").document(groupId).update("admins", change)
                            .addOnSuccessListener(v -> loadGroup())
                            .addOnFailureListener(e -> toast("تعذّر تحديث الصلاحيات"));
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void removeMember(String memberId) {
        if (!admin || memberId.equals(ownerId)) return;
        new AlertDialog.Builder(this)
                .setTitle("إزالة العضو")
                .setMessage("هل تريد إزالة هذا العضو من المجموعة؟")
                .setPositiveButton("إزالة", (d, w) -> {
                    DocumentReference ref = db.collection("chats").document(groupId);
                    db.runTransaction(transaction -> {
                        DocumentSnapshot snapshot = transaction.get(ref);
                        ArrayList<String> next = stringList(snapshot.get("participants"));
                        next.remove(memberId);
                        ArrayList<String> nextAdmins = stringList(snapshot.get("admins"));
                        nextAdmins.remove(memberId);
                        Map<String, Object> unread = snapshot.get("unreadCounts") instanceof Map
                                ? new HashMap<>((Map<String, Object>) snapshot.get("unreadCounts")) : new HashMap<>();
                        unread.remove(memberId);
                        transaction.update(ref, "participants", next, "admins", nextAdmins,
                                "unreadCounts", unread, "updatedAt", System.currentTimeMillis());
                        return null;
                    }).addOnSuccessListener(v -> loadGroup())
                            .addOnFailureListener(e -> toast("تعذّرت إزالة العضو"));
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void transferOwnership(String memberId) {
        if (!owner) return;
        new AlertDialog.Builder(this)
                .setTitle("نقل ملكية المجموعة")
                .setMessage("سيصبح هذا العضو مالك المجموعة، وستبقى مشرفاً.")
                .setPositiveButton("نقل الملكية", (d, w) -> {
                    ArrayList<String> nextAdmins = new ArrayList<>(admins);
                    if (!nextAdmins.contains(memberId)) nextAdmins.add(memberId);
                    db.collection("chats").document(groupId).update("ownerId", memberId, "admins", nextAdmins)
                            .addOnSuccessListener(v -> loadGroup())
                            .addOnFailureListener(e -> toast("تعذّر نقل الملكية"));
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void rotateInvite() {
        if (!admin) return;
        String newCode = newInviteCode();
        DocumentReference groupRef = db.collection("chats").document(groupId);
        db.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(groupRef);
            String oldCode = snapshot.getString("inviteCode");
            DocumentReference newInvite = db.collection("groupInvites").document(newCode);
            Map<String, Object> invite = new HashMap<>();
            invite.put("groupId", groupId);
            invite.put("active", true);
            invite.put("createdBy", uid);
            invite.put("createdAt", System.currentTimeMillis());
            transaction.set(newInvite, invite);
            transaction.update(groupRef, "inviteCode", newCode);
            if (oldCode != null && !oldCode.isEmpty()) {
                transaction.update(db.collection("groupInvites").document(oldCode), "active", false);
            }
            return null;
        }).addOnSuccessListener(v -> {
            loadGroup();
            toast("تم إنشاء رابط دعوة جديد وإلغاء السابق");
        }).addOnFailureListener(e -> toast("تعذّر إنشاء رابط الدعوة"));
    }

    private void shareInvite() {
        if (!admin) {
            toast("رابط الدعوة متاح للمشرفين فقط");
            return;
        }
        db.collection("chats").document(groupId).get()
                .addOnSuccessListener(snapshot -> {
                    String code = snapshot.getString("inviteCode");
                    if (code == null || code.isEmpty()) {
                        rotateInvite();
                        return;
                    }
                    String link = "babylon://join/" + code;
                    String groupName = snapshot.getString("name");
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_TEXT, "انضم إلى مجموعة " + (groupName == null ? "" : groupName) + "\n" + link);
                    startActivity(Intent.createChooser(share, "مشاركة رابط المجموعة"));
                })
                .addOnFailureListener(e -> toast("تعذّر تحميل رابط الدعوة"));
    }

    private void leaveGroup() {
        if (owner) {
            toast("انقل الملكية إلى عضو آخر قبل المغادرة، أو احذف المجموعة");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("مغادرة المجموعة")
                .setMessage("لن تظهر لك رسائل المجموعة بعد المغادرة.")
                .setPositiveButton("مغادرة", (d, w) -> {
                    DocumentReference ref = db.collection("chats").document(groupId);
                    db.runTransaction(transaction -> {
                        DocumentSnapshot snapshot = transaction.get(ref);
                        ArrayList<String> next = stringList(snapshot.get("participants"));
                        next.remove(uid);
                        ArrayList<String> nextAdmins = stringList(snapshot.get("admins"));
                        nextAdmins.remove(uid);
                        Map<String, Object> unread = snapshot.get("unreadCounts") instanceof Map
                                ? new HashMap<>((Map<String, Object>) snapshot.get("unreadCounts")) : new HashMap<>();
                        unread.remove(uid);
                        transaction.update(ref, "participants", next, "admins", nextAdmins,
                                "unreadCounts", unread, "updatedAt", System.currentTimeMillis());
                        return null;
                    }).addOnSuccessListener(v -> {
                                setResult(RESULT_OK, new Intent().putExtra("left_group", true));
                                finish();
                            })
                            .addOnFailureListener(e -> toast("تعذّرت مغادرة المجموعة"));
                })
                .setNegativeButton("إلغاء", null).show();
    }

    private void deleteGroup() {
        if (!owner) return;
        new AlertDialog.Builder(this)
                .setTitle("حذف المجموعة")
                .setMessage("سيتم حذف المجموعة ورسائلها نهائياً لكل الأعضاء.")
                .setPositiveButton("حذف نهائياً", (d, w) -> deleteMessagesPage())
                .setNegativeButton("إلغاء", null).show();
    }

    private void deleteMessagesPage() {
        db.collection("chats").document(groupId).collection("messages").limit(400).get()
                .addOnSuccessListener(snapshot -> {
                    if (snapshot.isEmpty()) {
                        deleteGroupDocuments();
                        return;
                    }
                    com.google.firebase.firestore.WriteBatch batch = db.batch();
                    for (DocumentSnapshot message : snapshot.getDocuments()) batch.delete(message.getReference());
                    batch.commit().addOnSuccessListener(v -> deleteMessagesPage())
                            .addOnFailureListener(e -> toast("تعذّر حذف رسائل المجموعة"));
                })
                .addOnFailureListener(e -> toast("تعذّر تحميل رسائل المجموعة للحذف"));
    }

    private void deleteGroupDocuments() {
        db.collection("groupInvites").whereEqualTo("groupId", groupId).limit(400).get()
                .addOnSuccessListener(invites -> {
                    if (invites.isEmpty()) {
                        db.collection("chats").document(groupId).delete().addOnSuccessListener(v -> {
                            setResult(RESULT_OK, new Intent().putExtra("deleted_group", true));
                            finish();
                        }).addOnFailureListener(e -> toast("تعذّر حذف المجموعة"));
                        return;
                    }
                    com.google.firebase.firestore.WriteBatch batch = db.batch();
                    for (DocumentSnapshot invite : invites.getDocuments()) batch.delete(invite.getReference());
                    batch.commit().addOnSuccessListener(v -> deleteGroupDocuments())
                            .addOnFailureListener(e -> toast("تعذّر حذف روابط الدعوة"));
                })
                .addOnFailureListener(e -> toast("تعذّر حذف روابط الدعوة"));
    }

    private void pickPhoto() {
        if (managing && !admin) return;
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(pick, "اختر صورة المجموعة"), PICK_GROUP_PHOTO);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_GROUP_PHOTO || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        try {
            photoData = encodePhoto(data.getData());
            showPhoto();
        } catch (Exception e) {
            toast("تعذّر تحميل الصورة");
        }
    }

    private String encodePhoto(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot read selected image");
            BitmapFactory.decodeStream(input, null, bounds);
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2;
        Bitmap source;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot read selected image");
            source = BitmapFactory.decodeStream(input, null, options);
        }
        if (source == null) throw new IllegalArgumentException("Invalid image");
        int side = Math.min(source.getWidth(), source.getHeight());
        Bitmap square = Bitmap.createBitmap(source, (source.getWidth() - side) / 2,
                (source.getHeight() - side) / 2, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, 320, 320, true);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!scaled.compress(Bitmap.CompressFormat.JPEG, 74, output)) throw new IllegalStateException("Image compression failed");
        if (square != source && !square.isRecycled()) square.recycle();
        if (source != scaled && !source.isRecycled()) source.recycle();
        if (scaled != source && !scaled.isRecycled()) scaled.recycle();
        return android.util.Base64.encodeToString(output.toByteArray(), android.util.Base64.NO_WRAP);
    }

    private void showPhoto() {
        if (photoImage == null) return;
        if (photoData == null || photoData.isEmpty()) {
            photoImage.setImageDrawable(null);
            photoImage.setVisibility(View.GONE);
            photoGlyph.setVisibility(View.VISIBLE);
            return;
        }
        try {
            byte[] bytes = android.util.Base64.decode(photoData, android.util.Base64.DEFAULT);
            Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bitmap == null) return;
            photoImage.setImageBitmap(bitmap);
            photoImage.setVisibility(View.VISIBLE);
            photoGlyph.setVisibility(View.GONE);
        } catch (IllegalArgumentException e) {
            toast("تعذّر تحميل صورة المجموعة");
        }
    }

    private String newInviteCode() {
        byte[] bytes = new byte[18];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) out.append(String.format(Locale.ROOT, "%02x", value & 0xFF));
        return out.toString();
    }

    private ArrayList<String> stringList(Object value) {
        ArrayList<String> out = new ArrayList<>();
        if (value instanceof List) for (Object item : (List<?>) value) out.add(String.valueOf(item));
        return out;
    }

    private LinearLayout card(int color, int radius) {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setBackground(round(color, radius));
        view.setElevation(dp(2));
        return view;
    }

    private EditText input(String hint, int size, int ink, int muted, int color) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setTextSize(size);
        edit.setTextColor(ink);
        edit.setHintTextColor(muted);
        edit.setSingleLine(true);
        edit.setPadding(dp(13), 0, dp(13), 0);
        edit.setBackground(round(AuthTheme.isDark(this) ? 0xFF101A25 : 0xFFF7F4EE, dp(13)));
        edit.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        return edit;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        return view;
    }

    private TextView actionText(String value, boolean dark) {
        TextView view = text(value, 12, GOLD, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(5), 0, dp(5), 0);
        return view;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (color == GOLD) drawable.setStroke(dp(1), 0x66FFFFFF);
        return drawable;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private LinearLayout.LayoutParams bottomMargin(int width, int height, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.bottomMargin = dp(margin);
        return params;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void finishWithError(String message) {
        toast(message);
        finish();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
