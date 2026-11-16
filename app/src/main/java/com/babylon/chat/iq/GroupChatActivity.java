package com.babylon.chat.iq;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
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
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Group conversation view, kept separate from the individual-chat read-receipt flow. */
public class GroupChatActivity extends Activity {

    private static final int PICK_IMAGE = 702;
    private static final int MAX_IMAGE_BYTES = 500 * 1024;
    private FirebaseFirestore db;
    private FirebaseUser user;
    private String uid;
    private String groupId;
    private String inviteCode;
    private String userName = "عضو";
    private String groupName = "مجموعة";
    private boolean joined;
    private boolean loading;
    private ListenerRegistration groupListener;
    private ListenerRegistration messagesListener;
    private final Map<String, String> nameCache = new HashMap<>();
    private LinearLayout root;
    private LinearLayout messages;
    private ScrollView scroll;
    private EditText input;
    private TextView sendButton;
    private TextView headerTitle;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        db = FirebaseFirestore.getInstance();
        user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        uid = user.getUid();
        groupId = getIntent().getStringExtra("group_id");
        inviteCode = getIntent().getStringExtra("join_code");
        buildScreen();
        db.collection("users").document(uid).get()
                .addOnSuccessListener(profile -> {
                    if (profile.getString("name") != null) userName = profile.getString("name");
                });
        if (inviteCode != null && !inviteCode.isEmpty()) resolveInvite();
        else if (groupId != null && !groupId.isEmpty()) listenGroup();
        else finish();
    }

    private void buildScreen() {
        boolean dark = AuthTheme.isDark(this);
        int ink = dark ? 0xFFF2F4F7 : 0xFF1A2734;
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setBackgroundColor(dark ? 0xFF101A25 : 0xFFF5F1E8);
        setContentView(root);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(13), dp(10), dp(13), dp(10));
        header.setBackgroundColor(dark ? 0xFF172432 : 0xFFFFFFFF);
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(62)));
        TextView back = label("‹", 32, ink, true);
        back.setGravity(Gravity.CENTER);
        header.addView(back, new LinearLayout.LayoutParams(dp(42), -1));
        back.setOnClickListener(v -> finish());
        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLp.setMarginStart(dp(8));
        header.addView(titleBox, titleLp);
        headerTitle = label(groupName, 17, ink, true);
        titleBox.addView(headerTitle);
        titleBox.addView(label("محادثة جماعية", 12, dark ? 0xFF9BA9B7 : 0xFF728092, false));
        TextView info = label("ⓘ", 23, 0xFFD8B34A, true);
        info.setGravity(Gravity.CENTER);
        header.addView(info, new LinearLayout.LayoutParams(dp(42), -1));
        info.setOnClickListener(v -> openGroupSettings());

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(13), dp(12), dp(13), dp(12));
        scroll.addView(messages, new ScrollView.LayoutParams(-1, -2));

        LinearLayout composer = new LinearLayout(this);
        composer.setGravity(Gravity.CENTER_VERTICAL);
        composer.setPadding(dp(10), dp(8), dp(10), dp(8));
        composer.setBackgroundColor(dark ? 0xFF172432 : 0xFFFFFFFF);
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        TextView attach = label("＋", 25, 0xFFD8B34A, true);
        attach.setGravity(Gravity.CENTER);
        composer.addView(attach, new LinearLayout.LayoutParams(dp(42), dp(48)));
        attach.setOnClickListener(v -> chooseImage());
        input = new EditText(this);
        input.setHint("اكتب رسالة…");
        input.setTextColor(ink);
        input.setHintTextColor(dark ? 0xFF8798AA : 0xFF728092);
        input.setTextSize(15);
        input.setMaxLines(5);
        input.setGravity(Gravity.CENTER_VERTICAL | Gravity.RIGHT);
        input.setPadding(dp(14), dp(9), dp(14), dp(9));
        input.setBackground(round(dark ? 0xFF101A25 : 0xFFF5F1E8, dp(20)));
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1f));
        sendButton = label("➤", 21, 0xFF17212B, true);
        sendButton.setGravity(Gravity.CENTER);
        sendButton.setBackground(oval(0xFFD8B34A));
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(dp(43), dp(43));
        sendLp.setMarginStart(dp(8));
        composer.addView(sendButton, sendLp);
        sendButton.setOnClickListener(v -> sendText());
    }

    private void resolveInvite() {
        db.collection("groupInvites").document(inviteCode).get()
                .addOnSuccessListener(invite -> {
                    if (!invite.exists() || !Boolean.TRUE.equals(invite.getBoolean("active"))) {
                        showError("رابط الدعوة غير صالح أو تم إلغاؤه");
                        return;
                    }
                    groupId = invite.getString("groupId");
                    if (groupId == null || groupId.isEmpty()) {
                        showError("رابط الدعوة غير صالح");
                        return;
                    }
                    listenGroup();
                })
                .addOnFailureListener(e -> showError("تعذّر فتح رابط الدعوة"));
    }

    private void listenGroup() {
        if (groupListener != null) groupListener.remove();
        groupListener = db.collection("chats").document(groupId).addSnapshotListener((snapshot, error) -> {
            if (error != null) {
                showError("تعذّر تحميل المجموعة");
                return;
            }
            if (snapshot == null || !snapshot.exists() || !"group".equals(snapshot.getString("type"))) {
                showError("المجموعة غير موجودة");
                return;
            }
            groupName = snapshot.getString("name") == null ? "مجموعة" : snapshot.getString("name");
            headerTitle.setText(groupName);
            List<?> participants = snapshot.get("participants") instanceof List ? (List<?>) snapshot.get("participants") : new ArrayList<>();
            if (!participants.contains(uid)) {
                if (inviteCode != null && !inviteCode.isEmpty()) confirmJoin(snapshot);
                else showError("لم تعد عضواً في هذه المجموعة");
                return;
            }
            joined = true;
            setResult(RESULT_OK);
            resetUnread(snapshot);
            if (messagesListener == null) listenMessages();
        });
    }

    private void confirmJoin(DocumentSnapshot snapshot) {
        if (loading || isFinishing()) return;
        loading = true;
        new AlertDialog.Builder(this)
                .setTitle("الانضمام إلى المجموعة")
                .setMessage("هل تريد الانضمام إلى «" + groupName + "»؟")
                .setPositiveButton("انضمام", (dialog, which) -> joinGroup())
                .setNegativeButton("إلغاء", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void joinGroup() {
        DocumentReference ref = db.collection("chats").document(groupId);
        db.runTransaction(transaction -> {
            DocumentSnapshot snapshot = transaction.get(ref);
            if (!snapshot.exists() || !"group".equals(snapshot.getString("type"))) throw new IllegalStateException("GROUP_NOT_FOUND");
            String code = snapshot.getString("inviteCode");
            if (code == null || !code.equals(inviteCode)) throw new IllegalStateException("INVITE_REVOKED");
            List<?> raw = snapshot.get("participants") instanceof List ? (List<?>) snapshot.get("participants") : new ArrayList<>();
            if (raw.contains(uid)) return null;
            Number limit = snapshot.getLong("memberLimit");
            int maximum = limit == null ? 100 : limit.intValue();
            if (raw.size() >= maximum) throw new IllegalStateException("GROUP_FULL");
            ArrayList<String> participants = new ArrayList<>();
            for (Object member : raw) participants.add(String.valueOf(member));
            participants.add(uid);
            Map<String, Object> unread = snapshot.get("unreadCounts") instanceof Map
                    ? new HashMap<>((Map<String, Object>) snapshot.get("unreadCounts")) : new HashMap<>();
            unread.put(uid, 0L);
            transaction.update(ref, "participants", participants, "unreadCounts", unread, "updatedAt", System.currentTimeMillis());
            return null;
        }).addOnSuccessListener(v -> {
            loading = false;
            inviteCode = null;
            listenGroup();
        }).addOnFailureListener(e -> {
            loading = false;
            showError(e.getMessage() != null && e.getMessage().contains("GROUP_FULL")
                    ? "وصلت المجموعة إلى الحد الأقصى للأعضاء"
                    : "تعذّر الانضمام؛ ربما تم إلغاء الرابط");
        });
    }

    private void resetUnread(DocumentSnapshot snapshot) {
        Object raw = snapshot.get("unreadCounts");
        if (!(raw instanceof Map) || !((Map<?, ?>) raw).containsKey(uid)) return;
        Object count = ((Map<?, ?>) raw).get(uid);
        if (!(count instanceof Number) || ((Number) count).intValue() == 0) return;
        Map<String, Object> unread = new HashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) unread.put(String.valueOf(entry.getKey()), entry.getValue());
        unread.put(uid, 0L);
        db.collection("chats").document(groupId).update("unreadCounts", unread);
    }

    private void listenMessages() {
        messagesListener = db.collection("chats").document(groupId).collection("messages")
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        if (!isFinishing()) toast("تعذّر تحميل رسائل المجموعة");
                        return;
                    }
                    if (snapshots == null || isFinishing()) return;
                    messages.removeAllViews();
                    for (DocumentSnapshot message : snapshots.getDocuments()) {
                        String sender = message.getString("sender");
                        if (sender != null && !uid.equals(sender)) {
                            List<?> readBy = message.get("readBy") instanceof List ? (List<?>) message.get("readBy") : new ArrayList<>();
                            if (!readBy.contains(uid)) message.getReference().update("readBy", FieldValue.arrayUnion(uid));
                        }
                        renderMessage(message);
                    }
                    scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
                });
    }

    private void renderMessage(DocumentSnapshot message) {
        String sender = message.getString("sender");
        boolean mine = uid.equals(sender);
        String type = message.getString("type");
        String text = message.getString("text");
        String data = message.getString("data");
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(dp(11), dp(7), dp(11), dp(7));
        int surface = AuthTheme.isDark(this) ? (mine ? 0xFF29435B : 0xFF1B2A38) : (mine ? 0xFFF0DFAE : 0xFFFFFFFF);
        block.setBackground(round(surface, dp(17)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams((int) (getResources().getDisplayMetrics().widthPixels * .76f), -2);
        bp.gravity = mine ? Gravity.RIGHT : Gravity.LEFT;
        bp.bottomMargin = dp(7);
        messages.addView(block, bp);
        if (!mine && sender != null) {
            String senderName = nameCache.containsKey(sender) ? nameCache.get(sender) : "عضو";
            TextView senderLabel = label(senderName, 12, 0xFFD8B34A, true);
            block.addView(senderLabel);
            if (!nameCache.containsKey(sender)) {
                db.collection("users").document(sender).get().addOnSuccessListener(profile -> {
                    String name = profile.getString("name");
                    if (name != null && !name.isEmpty()) {
                        nameCache.put(sender, name);
                        senderLabel.setText(name);
                    }
                });
            }
        }
        if ("image".equals(type) && data != null && !data.isEmpty()) {
            try {
                byte[] bytes = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                if (bitmap != null) {
                    ImageView image = new ImageView(this);
                    image.setImageBitmap(bitmap);
                    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    image.setAdjustViewBounds(true);
                    block.addView(image, new LinearLayout.LayoutParams(-1, dp(210)));
                }
            } catch (IllegalArgumentException e) {
                block.addView(label("تعذّر عرض الصورة", 14, 0xFFE36A6A, false));
            }
        } else if (text != null) {
            TextView body = label(text, 15, AuthTheme.isDark(this) ? 0xFFF2F4F7 : 0xFF1A2734, false);
            body.setGravity(Gravity.RIGHT);
            body.setMaxWidth((int) (getResources().getDisplayMetrics().widthPixels * .70f));
            block.addView(body);
        }
        Long timestamp = message.getLong("timestamp");
        if (timestamp != null) {
            TextView time = label(new java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(timestamp), 10,
                    AuthTheme.isDark(this) ? 0xFF9BA9B7 : 0xFF728092, false);
            time.setGravity(Gravity.LEFT);
            block.addView(time);
        }
    }

    private void sendText() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() || !joined) return;
        input.setText("");
        Map<String, Object> message = new HashMap<>();
        message.put("type", "text");
        message.put("text", text);
        message.put("sender", uid);
        message.put("senderName", userName);
        message.put("timestamp", System.currentTimeMillis());
        message.put("readBy", new ArrayList<>(java.util.Collections.singletonList(uid)));
        sendMessage(message, text);
    }

    private void chooseImage() {
        if (!joined) return;
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.setType("image/*");
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(Intent.createChooser(pick, "اختر صورة"), PICK_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_IMAGE || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        sendImage(data.getData());
    }

    private void sendImage(Uri uri) {
        new Thread(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    if (stream == null) throw new IllegalStateException("Cannot read selected image");
                    BitmapFactory.decodeStream(stream, null, bounds);
                }
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 1;
                while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2;
                Bitmap source;
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    if (stream == null) throw new IllegalStateException("Cannot read selected image");
                    source = BitmapFactory.decodeStream(stream, null, options);
                }
                if (source == null) throw new IllegalArgumentException("Invalid image");
                float scale = Math.min(1f, 800f / Math.max(source.getWidth(), source.getHeight()));
                Bitmap scaled = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)),
                        Math.max(1, Math.round(source.getHeight() * scale)), true);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int quality = 78;
                while (quality >= 45) {
                    bytes.reset();
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
                    if (bytes.size() <= MAX_IMAGE_BYTES) break;
                    quality -= 8;
                }
                if (bytes.size() > MAX_IMAGE_BYTES) throw new IllegalArgumentException("Image exceeds limit");
                String base64 = android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
                if (source != scaled) source.recycle();
                scaled.recycle();
                runOnUiThread(() -> {
                    Map<String, Object> message = new HashMap<>();
                    message.put("type", "image");
                    message.put("data", base64);
                    message.put("sender", uid);
                    message.put("senderName", userName);
                    message.put("timestamp", System.currentTimeMillis());
                    message.put("readBy", new ArrayList<>(java.util.Collections.singletonList(uid)));
                    sendMessage(message, "📷 صورة");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("تعذّر إرسال الصورة"));
            }
        }).start();
    }

    private void sendMessage(Map<String, Object> message, String preview) {
        db.runTransaction(transaction -> {
            DocumentReference groupRef = db.collection("chats").document(groupId);
            DocumentSnapshot snapshot = transaction.get(groupRef);
            if (!snapshot.exists()) throw new IllegalStateException("GROUP_NOT_FOUND");
            List<?> raw = snapshot.get("participants") instanceof List ? (List<?>) snapshot.get("participants") : new ArrayList<>();
            if (!raw.contains(uid)) throw new IllegalStateException("NOT_A_MEMBER");
            Map<String, Object> unread = snapshot.get("unreadCounts") instanceof Map
                    ? new HashMap<>((Map<String, Object>) snapshot.get("unreadCounts")) : new HashMap<>();
            for (Object member : raw) {
                String memberId = String.valueOf(member);
                if (memberId.equals(uid)) continue;
                Object count = unread.get(memberId);
                unread.put(memberId, (count instanceof Number ? ((Number) count).longValue() : 0L) + 1L);
            }
            long now = System.currentTimeMillis();
            message.put("timestamp", now);
            message.put("readBy", new ArrayList<>(java.util.Collections.singletonList(uid)));
            DocumentReference messageRef = groupRef.collection("messages").document();
            transaction.set(messageRef, message);
            transaction.update(groupRef, "lastMessage", preview, "lastMessageAt", now, "lastSender", uid,
                    "updatedAt", now, "unreadCounts", unread);
            return null;
        }).addOnFailureListener(e -> {
            if ("text".equals(message.get("type")) && input.getText().length() == 0) {
                input.setText(String.valueOf(message.get("text")));
            }
            toast("تعذّر إرسال الرسالة؛ تحقق من عضويتك");
        });
    }

    private void openGroupSettings() {
        if (!joined) return;
        Intent intent = new Intent(this, GroupActivity.class);
        intent.putExtra("manage_group", true);
        intent.putExtra("group_id", groupId);
        startActivity(intent);
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        text.setIncludeFontPadding(false);
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        return text;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable oval(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showError(String message) {
        toast(message);
        finish();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (groupListener != null) groupListener.remove();
        if (messagesListener != null) messagesListener.remove();
        super.onDestroy();
    }
}
