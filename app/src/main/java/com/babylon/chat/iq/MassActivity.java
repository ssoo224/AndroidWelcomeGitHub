package com.babylon.chat.iq;

import android.animation.*;
import android.app.*;
import android.app.Activity;
import android.content.*;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.view.View.*;
import android.view.animation.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.babylon.chat.iq.databinding.*;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.*;

public class MassActivity extends Activity {

    private MassBinding binding;
    private FirebaseAuth mAuth;
    private FirebaseUser currentUser;
    private FirebaseFirestore db;
    private FirebaseDatabase rtdb;
    private String myUid = "";

    private DatabaseReference currentChatRef;
    private ChildEventListener currentChatListener;
    private String currentChatId = "";
    private String otherUid = "";

    private ListenerRegistration chatsListener;

    // للتعامل مع نتائج اختيار الصورة وتسجيل الصوت
    private java.util.function.Consumer<Uri> pendingImageCallback = null;
    private Runnable pendingMicCallback = null;
    private static final int REQ_PICK_IMAGE = 1001;
    private static final int REQ_MIC_PERM = 1002;

    @Override
    protected void onCreate(Bundle _savedInstanceState) {
        super.onCreate(_savedInstanceState);
        binding = MassBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        mAuth = FirebaseAuth.getInstance();
        currentUser = mAuth.getCurrentUser();
        db = FirebaseFirestore.getInstance();
        rtdb = FirebaseDatabase.getInstance();

        if (currentUser == null) {
            startActivity(new Intent(this, Main2Activity.class));
            finish();
            return;
        }
        myUid = currentUser.getUid();

        initializeLogic();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentChatRef != null && currentChatListener != null)
            currentChatRef.removeEventListener(currentChatListener);
        if (chatsListener != null) chatsListener.remove();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            java.util.function.Consumer<Uri> cb = pendingImageCallback;
            pendingImageCallback = null;
            if (cb != null) cb.accept(data.getData());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MIC_PERM) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Runnable cb = pendingMicCallback;
                pendingMicCallback = null;
                if (cb != null) cb.run();
            } else {
                Toast.makeText(this, "يجب منح إذن الميكروفون لتسجيل الصوت", Toast.LENGTH_SHORT).show();
                pendingMicCallback = null;
            }
        }
    }

    private void initializeLogic() {
        final Activity act = this;
        act.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        class UI {
            SharedPreferences sp = act.getSharedPreferences("babylon_ui", MODE_PRIVATE);
            Handler h = new Handler(Looper.getMainLooper());
            boolean dark = sp.getBoolean("dark", false);
            int fontSp = sp.getInt("font", 15);
            int radius = sp.getInt("radius", 16); // زوايا احترافية افتراضية
            int chatBgIdx = sp.getInt("chatBg", 0);
            int coverColor = sp.getInt("cover", 0xFF1B2A4A);
            boolean unreadOnly = false, muted = false, blocked = false;
            String curPage = "homeView", prevPage = "homeView", curChat = "";
            String replyText = null, otherName = "", otherUser = "";
            TextView editing = null;

            // الصوت
            MediaRecorder recorder = null;
            File audioFile = null;
            boolean isRecording = false;
            long recordStart = 0;
            Handler recHandler = new Handler(Looper.getMainLooper());
            Runnable recTick = null;
            MediaPlayer mediaPlayer = null;

            String[] pages = {"homeView", "searchView", "profileView", "otherUserProfileView",
                    "settingsView", "privacySettingsView", "notificationSettingsView",
                    "chatSettingsView", "editProfileView"};
            String[] govs = {"بغداد", "البصرة", "نينوى", "أربيل", "النجف", "كربلاء", "السليمانية", "دهوك",
                    "كركوك", "الأنبار", "ديالى", "صلاح الدين", "بابل", "واسط", "ذي قار", "ميسان", "المثنى", "القادسية"};
            String[] themeNames = {"افتراضي", "أزرق", "أخضر", "بنفسجي", "ذهبي"};
            int[] bgLight = {0xFFE4E9F2, 0xFFD6E6FA, 0xFFDDF0DD, 0xFFE8DDF3, 0xFFF5EBCB};
            int[] bgDark = {0xFF0E1621, 0xFF0B1B30, 0xFF0C1F14, 0xFF1A1128, 0xFF241C08};
            int[] covers = {0xFF1B2A4A, 0xFF3B2A4A, 0xFF0F5132, 0xFF7C2D12, 0xFF334155, 0xFFB8891A};

            ArrayList<String[]> chatData = new ArrayList<>();

            String userName = "", userUsername = "", userBio = "", userGov = "", userEmail = "", userJoin = "";

            int dp(float d) { return (int) (d * act.getResources().getDisplayMetrics().density + 0.5f); }
            View v(String n) { return act.findViewById(act.getResources().getIdentifier(n, "id", act.getPackageName())); }
            TextView tv(String n) { return (TextView) v(n); }
            EditText et(String n) { return (EditText) v(n); }
            String txt(String n) { return et(n).getText().toString().trim(); }
            void toast(String s) { Toast.makeText(act, s, Toast.LENGTH_SHORT).show(); }
            void vis(String n, boolean on) { View x = v(n); if (x != null) x.setVisibility(on ? View.VISIBLE : View.GONE); }
            String now() { return new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date()); }
            void hideKb() {
                try {
                    InputMethodManager m = (InputMethodManager) act.getSystemService(Context.INPUT_METHOD_SERVICE);
                    m.hideSoftInputFromWindow(v("root").getWindowToken(), 0);
                } catch (Exception e) { }
            }
            GradientDrawable shape(int fill, float rDp, int stroke) {
                GradientDrawable g = new GradientDrawable();
                g.setColor(fill);
                g.setCornerRadius(dp(rDp));
                if (stroke != 0) g.setStroke(dp(1.2f), stroke);
                return g;
            }

            int cBg() { return dark ? 0xFF0F172A : 0xFFF0F2F5; }
            int cSf() { return dark ? 0xFF17212B : 0xFFFFFFFF; }
            int cBorder() { return dark ? 0xFF344256 : 0xFFE1E7EF; }
            int cText() { return dark ? 0xFFF1F5F9 : 0xFF17212B; }
            int cMuted() { return dark ? 0xFFA5B2C2 : 0xFF708094; }
            int cGold() { return dark ? 0xFFE4C45B : 0xFFC29D30; }
            int cRed() { return dark ? 0xFFFB7185 : 0xFFDC2626; }
            int cMine() { return dark ? 0xFF2B5278 : 0xFFD9FDD3; }
            int cOther() { return dark ? 0xFF182533 : 0xFFFFFFFF; }
            int cChatBg() { return dark ? bgDark[chatBgIdx] : bgLight[chatBgIdx]; }

            void style(View x) {
                if (x == null) return;
                Object t = x.getTag();
                if (t instanceof String) {
                    String[] ks = ((String) t).split(" ");
                    for (String k : ks) {
                        TextView T = (x instanceof TextView) ? (TextView) x : null;
                        if (k.equals("bg")) x.setBackgroundColor(cBg());
                        else if (k.equals("sf")) x.setBackgroundColor(cSf());
                        else if (k.equals("line")) x.setBackgroundColor(cBorder());
                        else if (k.equals("chatbg")) x.setBackgroundColor(cChatBg());
                        else if (k.equals("card")) x.setBackground(shape(cSf(), 16, cBorder()));
                        else if (k.equals("inp")) {
                            x.setBackground(shape(dark ? 0xFF0F172A : 0xFFF7F9FC, 22, cBorder()));
                            if (T != null) { T.setTextColor(cText()); T.setHintTextColor(cMuted()); }
                        }
                        else if (k.equals("gold")) { x.setBackground(shape(cGold(), 12, 0)); if (T != null) T.setTextColor(0xFF17212B); }
                        else if (k.equals("goldo")) { x.setBackground(shape(0, 12, cGold())); if (T != null) T.setTextColor(cGold()); }
                        else if (k.equals("redo")) { x.setBackground(shape(0, 12, cRed())); if (T != null) T.setTextColor(cRed()); }
                        else if (k.equals("circ")) {
                            GradientDrawable g = new GradientDrawable();
                            g.setShape(GradientDrawable.OVAL);
                            g.setColor(cGold());
                            x.setBackground(g);
                        } else if (k.equals("circRed")) {
                            GradientDrawable g = new GradientDrawable();
                            g.setShape(GradientDrawable.OVAL);
                            g.setColor(cRed());
                            x.setBackground(g);
                        } else if (k.equals("ring")) {
                            GradientDrawable g = new GradientDrawable();
                            g.setShape(GradientDrawable.OVAL);
                            g.setColor(cSf());
                            g.setStroke(dp(2.5f), cGold());
                            x.setBackground(g);
                            if (T != null) T.setTextColor(cGold());
                        } else if (k.equals("bubMine")) {
                            x.setBackground(shape(cMine(), radius, 0));
                            x.setElevation(dp(1));
                            if (T != null) T.setTextColor(dark ? 0xFFFFFFFF : 0xFF17212B);
                        } else if (k.equals("bubOther")) {
                            x.setBackground(shape(cOther(), radius, dark ? 0 : 0xFFE1E7EF));
                            x.setElevation(dp(1));
                            if (T != null) T.setTextColor(cText());
                        } else if (k.startsWith("ic:")) x.setBackground(shape(Color.parseColor(k.substring(3)), 12, 0));
                        else if (k.startsWith("cover:")) x.setBackgroundColor(Color.parseColor(k.substring(6)));
                        else if (T != null) {
                            if (k.equals("t")) T.setTextColor(cText());
                            else if (k.equals("m")) T.setTextColor(cMuted());
                            else if (k.equals("g")) T.setTextColor(cGold());
                            else if (k.equals("red")) T.setTextColor(cRed());
                            else if (k.equals("green")) T.setTextColor(0xFF22C55E);
                            else if (k.equals("wh")) T.setTextColor(0xFFFFFFFF);
                        }
                    }
                }
                if (x instanceof TextView) tintIcons((TextView) x);
                if (x instanceof ViewGroup) {
                    ViewGroup g = (ViewGroup) x;
                    for (int i = 0; i < g.getChildCount(); i++) style(g.getChildAt(i));
                }
            }
            void tintIcons(TextView T) {
                android.graphics.drawable.Drawable[] ds = T.getCompoundDrawablesRelative();
                for (android.graphics.drawable.Drawable d : ds) {
                    if (d != null) d.mutate().setColorFilter(T.getCurrentTextColor(), android.graphics.PorterDuff.Mode.SRC_IN);
                }
            }
            void setLabel(String id, String label, int iconRes) {
                TextView T = tv(id);
                T.setText(label);
                T.setCompoundDrawablePadding(dp(6));
                T.setCompoundDrawablesRelativeWithIntrinsicBounds(iconRes, 0, 0, 0);
                tintIcons(T);
            }
            int icon(String name, int size) { return act.getResources().getIdentifier(name + "_" + size, "drawable", act.getPackageName()); }
            void setOn(View b, boolean on, String offTag) {
                b.setBackgroundColor(Color.TRANSPARENT);
                b.setTag(on ? "gold" : offTag);
                style(b);
            }

            void applyTheme() {
                style(v("root"));
                v("pvBox").setBackgroundColor(cChatBg());
                ((View) v("pCoverTxt").getParent()).setBackgroundColor(coverColor);
                tv("themeDescription").setText(dark ? "الوضع الليلي مفعّل" : "الوضع النهاري مفعّل");
                setLabel("settingsThemeToggle", dark ? "نهاري" : "ليلي", icon(dark ? "ic_sun" : "ic_moon", 16));
                buildThemeStrip();
                setNav(curPage);
            }

            void show(String p) {
                prevPage = curPage;
                curPage = p;
                for (String page : pages) vis(page, page.equals(p));
                boolean home = p.equals("homeView");
                vis("mainHeader", home);
                vis("newGroupFab", home);
                vis("bottomNav", home || p.equals("settingsView") || p.equals("profileView"));
                setNav(p);
                hideKb();
            }
            void setNav(String p) {
                String[] ids = {"navChats", "navSettings", "navProfile"};
                String a2 = p.equals("homeView") ? "navChats" : p.equals("settingsView") ? "navSettings" : p.equals("profileView") ? "navProfile" : "";
                for (String id : ids) {
                    boolean on = id.equals(a2);
                    tv(id + "Ic").setTag(on ? "g" : "m");
                    tv(id + "Tx").setTag(on ? "g" : "m");
                    style(v(id));
                }
            }

            // ============ Firebase: profile ============
            void loadMyProfile() {
                db.collection("users").document(myUid).get()
                    .addOnSuccessListener(doc -> {
                        if (doc.exists()) {
                            userName = safe(doc.getString("name"));
                            userUsername = safe(doc.getString("username"));
                            userBio = safe(doc.getString("bio"));
                            userGov = safe(doc.getString("gov"));
                            userEmail = safe(doc.getString("email"));
                            Long createdAt = doc.getLong("createdAt");
                            userJoin = createdAt != null
                                ? new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(new Date(createdAt))
                                : new SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(new Date());
                            fillProfile();
                        }
                    });
            }
            String safe(String s) { return s != null ? s : ""; }

            void fillProfile() {
                String letter = userName.length() > 0 ? userName.substring(0, 1).toUpperCase() : "؟";
                tv("pAvatarText").setText(letter);
                tv("sxAvatar").setText(letter);
                tv("pHeaderName").setText(userName.length() > 0 ? userName + "  ✓" : "");
                tv("sxName").setText(userName);
                tv("pHeaderUser").setText(userUsername.length() > 0 ? "@" + userUsername : "");
                tv("sxUser").setText(userUsername.length() > 0 ? "@" + userUsername : "");
                tv("pBioDisplay").setText(userBio.length() > 0 ? userBio : "أضف نبذة عن نفسك");
                tv("sxBio").setText(userBio.length() > 0 ? userBio : "أضف نبذة عن نفسك");
                tv("pGovDisplay").setText(userGov);
                tv("pEmailDisplay").setText(userEmail);
                tv("pJoinDisplay").setText(userJoin);
            }
            void openEdit() {
                et("modalNameInput").setText(userName);
                et("modalUsernameInput").setText(userUsername);
                et("modalBioInput").setText(userBio);
                for (int i = 0; i < govs.length; i++)
                    if (govs[i].equals(userGov)) ((Spinner) v("modalGovInput")).setSelection(i);
                vis("modalUserError", false);
                show("editProfileView");
            }
            void saveProfile() {
                String n = txt("modalNameInput");
                String u = txt("modalUsernameInput").replace("@", "");
                String bio = txt("modalBioInput");
                String gov = govs[((Spinner) v("modalGovInput")).getSelectedItemPosition()];
                if (n.length() == 0) { toast("اكتب اسم العرض"); return; }
                if (u.length() < 5 || !u.matches("[A-Za-z0-9_]+")) {
                    tv("modalUserError").setText("اسم المستخدم 5 أحرف إنجليزية فأكثر");
                    vis("modalUserError", true);
                    return;
                }
                db.collection("users").whereEqualTo("username", u).limit(1).get()
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && task.getResult() != null && !task.getResult().isEmpty()) {
                            String takenBy = task.getResult().getDocuments().get(0).getId();
                            if (!takenBy.equals(myUid) && !u.equals(userUsername)) {
                                tv("modalUserError").setText("اسم المستخدم محجوز");
                                vis("modalUserError", true);
                                return;
                            }
                        }
                        Map<String, Object> updates = new HashMap<>();
                        updates.put("name", n); updates.put("username", u);
                        updates.put("bio", bio); updates.put("gov", gov);
                        db.collection("users").document(myUid).update(updates)
                            .addOnSuccessListener(a -> {
                                userName = n; userUsername = u; userBio = bio; userGov = gov;
                                fillProfile();
                                toast("تم حفظ التغييرات");
                                show("profileView");
                            })
                            .addOnFailureListener(e -> toast("فشل الحفظ: " + e.getMessage()));
                    });
            }

            void openOther(String name, String username, String uid) {
                otherName = name; otherUser = username; otherUid = uid;
                blocked = false;
                tv("otherAvatarText").setText(name.length() > 0 ? name.substring(0, 1) : "?");
                tv("otherHeaderName").setText(name);
                tv("otherHeaderUser").setText("@" + username);
                tv("otherBioDisplay").setText("");
                setLabel("otherBlockBtn", "حظر", icon("ic_block", 16));
                String back = curPage;
                show("otherUserProfileView");
                prevPage = back;
            }

            // ============ Firebase: chats ============
            void loadChats() {
                if (chatsListener != null) chatsListener.remove();
                chatsListener = db.collection("chats")
                    .whereArrayContains("participants", myUid)
                    .addSnapshotListener((snapshots, e) -> {
                        if (e != null || snapshots == null) return;
                        List<Map<String, Object>> temp = new ArrayList<>();
                        for (QueryDocumentSnapshot doc : snapshots) {
                            Map<String, Object> m = doc.getData();
                            m.put("__id", doc.getId());
                            temp.add(m);
                        }
                        Collections.sort(temp, (a, b) -> {
                            Long ta = (Long) a.get("lastMessageAt");
                            Long tb = (Long) b.get("lastMessageAt");
                            if (ta == null) ta = 0L;
                            if (tb == null) tb = 0L;
                            return Long.compare(tb, ta);
                        });
                        chatData.clear();
                        for (Map<String, Object> m : temp) {
                            List<String> parts = (List<String>) m.get("participants");
                            if (parts == null) continue;
                            String otherId = null;
                            for (String p : parts) if (!p.equals(myUid)) { otherId = p; break; }
                            if (otherId == null) continue;
                            final String fOtherId = otherId;
                            final String lastMsg = m.get("lastMessage") != null ? m.get("lastMessage").toString() : "";
                            final Long lastAt = (Long) m.get("lastMessageAt");
                            final String lastTime = lastAt != null ? new SimpleDateFormat("h:mm", Locale.getDefault()).format(new Date(lastAt)) : "";
                            db.collection("users").document(fOtherId).get()
                                .addOnSuccessListener(udoc -> {
                                    String oName = udoc.exists() && udoc.getString("name") != null ? udoc.getString("name") : "?";
                                    boolean found = false;
                                    for (int i = 0; i < chatData.size(); i++) {
                                        if (chatData.get(i)[4].equals(fOtherId)) {
                                            chatData.set(i, new String[]{oName, lastMsg, lastTime, "0", fOtherId});
                                            found = true;
                                            break;
                                        }
                                    }
                                    if (!found) chatData.add(new String[]{oName, lastMsg, lastTime, "0", fOtherId});
                                    buildChats();
                                    buildStories();
                                });
                        }
                    });
            }

            void buildStories() {
                LinearLayout bar = (LinearLayout) v("storiesBar");
                bar.removeAllViews();
                for (int i = 0; i <= chatData.size() && i < 8; i++) {
                    final String nm = i == 0 ? "قصتي" : chatData.get(i - 1)[0];
                    LinearLayout c = new LinearLayout(act);
                    c.setOrientation(LinearLayout.VERTICAL);
                    c.setGravity(Gravity.CENTER_HORIZONTAL);
                    c.setPadding(dp(6), 0, dp(6), 0);
                    TextView r = new TextView(act);
                    r.setText(i == 0 ? "+" : (nm.length() > 0 ? nm.substring(0, 1) : "?"));
                    r.setGravity(Gravity.CENTER);
                    r.setTextSize(20);
                    r.setTypeface(null, 1);
                    r.setTag("ring");
                    r.setLayoutParams(new LinearLayout.LayoutParams(dp(60), dp(60)));
                    TextView n = new TextView(act);
                    n.setText(nm.length() > 8 ? nm.substring(0, 8) : nm);
                    n.setTextSize(11);
                    n.setTag("m");
                    n.setPadding(0, dp(4), 0, 0);
                    c.addView(r); c.addView(n);
                    c.setOnClickListener(x -> toast(nm.equals("قصتي") ? "نشر قصة جديدة" : "عرض قصة " + nm));
                    bar.addView(c);
                    style(c);
                }
            }

            void buildChats() {
                LinearLayout list = (LinearLayout) v("chatList");
                list.removeAllViews();
                int unreadChats = 0;
                for (String[] c : chatData) if (!c[3].equals("0")) unreadChats++;
                tv("fltUnread").setText("غير المقروءة" + (unreadChats > 0 ? " (" + unreadChats + ")" : ""));
                for (String[] c : chatData) {
                    if (unreadOnly && c[3].equals("0")) continue;
                    LinearLayout row = new LinearLayout(act);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(12), dp(10), dp(12), dp(10));
                    row.setTag("card");
                    LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
                    rp.setMargins(dp(10), dp(5), dp(10), dp(5));
                    row.setLayoutParams(rp);
                    TextView av = new TextView(act);
                    av.setText(c[0].length() > 0 ? c[0].substring(0, 1) : "?");
                    av.setGravity(Gravity.CENTER);
                    av.setTextSize(20);
                    av.setTypeface(null, 1);
                    av.setTextColor(0xFF17212B);
                    av.setTag("circ");
                    av.setLayoutParams(new LinearLayout.LayoutParams(dp(52), dp(52)));
                    LinearLayout mid = new LinearLayout(act);
                    mid.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, -2, 1f);
                    mp.setMargins(dp(12), 0, dp(12), 0);
                    mid.setLayoutParams(mp);
                    TextView nm = new TextView(act);
                    nm.setText(c[0]); nm.setTextSize(16); nm.setTypeface(null, 1); nm.setTag("t");
                    TextView ls = new TextView(act);
                    ls.setText(c[1]); ls.setTextSize(13);
                    ls.setSingleLine(true); ls.setEllipsize(TextUtils.TruncateAt.END); ls.setTag("m");
                    mid.addView(nm); mid.addView(ls);
                    LinearLayout end = new LinearLayout(act);
                    end.setOrientation(LinearLayout.VERTICAL);
                    end.setGravity(Gravity.CENTER_HORIZONTAL);
                    TextView tm = new TextView(act);
                    tm.setText(c[2]); tm.setTextSize(11); tm.setTag("m");
                    end.addView(tm);
                    if (!c[3].equals("0")) {
                        TextView b = new TextView(act);
                        b.setText(c[3]); b.setTextSize(11); b.setTypeface(null, 1);
                        b.setGravity(Gravity.CENTER); b.setTag("gold");
                        b.setMinWidth(dp(22)); b.setMinHeight(dp(22));
                        end.addView(b);
                    }
                    row.addView(av); row.addView(mid); row.addView(end);
                    final String fName = c[0], fUid = c[4];
                    row.setOnClickListener(x -> openChat(fName, fUid));
                    row.setOnLongClickListener(x -> {
                        new AlertDialog.Builder(act).setTitle(fName)
                            .setItems(new String[]{"تعليم كمقروءة", "حذف المحادثة"}, (d, w) -> {
                                if (w == 1) {
                                    String cid = getChatId(myUid, fUid);
                                    db.collection("chats").document(cid).delete();
                                    rtdb.getReference("messages").child(cid).removeValue();
                                }
                            }).show();
                        return true;
                    });
                    list.addView(row);
                    style(row);
                }
                if (list.getChildCount() == 0) {
                    TextView e = new TextView(act);
                    e.setText("لا توجد محادثات");
                    e.setGravity(Gravity.CENTER);
                    e.setPadding(0, dp(60), 0, 0);
                    e.setTag("m");
                    list.addView(e);
                    style(e);
                }
            }

            void setFilter(boolean un) {
                unreadOnly = un;
                setOn(v("fltAll"), !un, "goldo");
                setOn(v("fltUnread"), un, "goldo");
                buildChats();
            }

            String getChatId(String u1, String u2) {
                return u1.compareTo(u2) < 0 ? u1 + "_" + u2 : u2 + "_" + u1;
            }

            void openChat(String name, String uid) {
                otherUid = uid; otherName = name; curChat = name;
                blocked = false; muted = false;
                tv("chatTitle").setText(name);
                tv("chatAvatar").setText(name.length() > 0 ? name.substring(0, 1) : "?");
                tv("chatSub").setText("متصل");
                ((LinearLayout) v("messagesArea")).removeAllViews();
                cancelReply();
                vis("chatSearchPanel", false);
                vis("recordingBar", false);

                if (currentChatRef != null && currentChatListener != null)
                    currentChatRef.removeEventListener(currentChatListener);

                currentChatId = getChatId(myUid, uid);
                loadMessages(currentChatId);
                vis("chatView", true);
            }

            void loadMessages(String chatId) {
                currentChatRef = rtdb.getReference("messages").child(chatId);
                currentChatListener = new ChildEventListener() {
                    @Override
                    public void onChildAdded(DataSnapshot snapshot, String prev) {
                        String type = snapshot.child("type").getValue(String.class);
                        if (type == null) type = "text";
                        String text = snapshot.child("text").getValue(String.class);
                        String url = snapshot.child("url").getValue(String.class);
                        String sender = snapshot.child("sender").getValue(String.class);
                        Long time = snapshot.child("timestamp").getValue(Long.class);
                        Long dur = snapshot.child("duration").getValue(Long.class);
                        if (text == null && url == null) return;
                        boolean mine = myUid.equals(sender);
                        String timeStr = new SimpleDateFormat("h:mm", Locale.getDefault())
                                .format(new Date(time != null ? time : System.currentTimeMillis()));

                        if (type.equals("image") && url != null) addImageMsg(url, mine, timeStr);
                        else if (type.equals("audio") && url != null) addAudioMsg(url, dur != null ? dur : 0, mine, timeStr);
                        else if (text != null) addMsg(text, mine, timeStr);
                        scrollDown();
                    }
                    @Override public void onChildChanged(DataSnapshot s, String p) { }
                    @Override public void onChildRemoved(DataSnapshot s) { }
                    @Override public void onChildMoved(DataSnapshot s, String p) { }
                    @Override public void onCancelled(DatabaseError e) { }
                };
                currentChatRef.addChildEventListener(currentChatListener);
            }

            void closeChat() {
                hideKb();
                vis("chatView", false);
                if (currentChatRef != null && currentChatListener != null)
                    currentChatRef.removeEventListener(currentChatListener);
                currentChatRef = null; currentChatListener = null;
                currentChatId = ""; otherUid = "";
                if (mediaPlayer != null) { try { mediaPlayer.release(); } catch (Exception e) {} mediaPlayer = null; }
                buildChats(); buildStories();
            }

            // رسالة نصية
            TextView addMsg(String text, final boolean mine, String time) {
                final TextView b = new TextView(act);
                b.setTag(mine ? "bubMine" : "bubOther");
                b.setTextSize(fontSp);
                b.setPadding(dp(12), dp(8), dp(12), dp(6));
                b.setMaxWidth((int) (act.getResources().getDisplayMetrics().widthPixels * 0.78f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.gravity = mine ? Gravity.END : Gravity.START;
                lp.setMargins(0, dp(3), 0, dp(3));
                b.setLayoutParams(lp);
                setMsg(b, text, mine, time);
                b.setOnLongClickListener(x -> { msgMenu(b, mine); return true; });
                ((LinearLayout) v("messagesArea")).addView(b);
                style(b);
                return b;
            }
            void setMsg(TextView b, String text, boolean mine, String time) {
                b.setContentDescription(text);
                SpannableStringBuilder sb = new SpannableStringBuilder(text);
                int s = sb.length();
                sb.append("\n" + time + (mine ? "  ✓✓" : ""));
                sb.setSpan(new RelativeSizeSpan(0.7f), s, sb.length(), 0);
                sb.setSpan(new ForegroundColorSpan(0xFF8696A7), s, sb.length(), 0);
                b.setText(sb);
            }

            // رسالة صورة
            void addImageMsg(String url, boolean mine, String time) {
                FrameLayout frame = new FrameLayout(act);
                int size = Math.min(dp(220), (int) (act.getResources().getDisplayMetrics().widthPixels * 0.68f));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
                lp.gravity = mine ? Gravity.END : Gravity.START;
                lp.setMargins(0, dp(3), 0, dp(3));
                frame.setLayoutParams(lp);
                frame.setClipToOutline(true);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(dark ? 0xFF202C33 : 0xFFF0F0F0);
                bg.setCornerRadius(dp(16));
                frame.setBackground(bg);
                frame.setOutlineProvider(new ViewOutlineProvider() {
                    @Override public void getOutline(View v, Outline o) {
                        o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(16));
                    }
                });
                ImageView img = new ImageView(act);
                img.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                frame.addView(img);
                loadImageAsync(img, url);
                TextView t = new TextView(act);
                t.setText(time);
                t.setTextSize(10);
                t.setTextColor(0xFFFFFFFF);
                t.setPadding(dp(6), dp(2), dp(6), dp(2));
                GradientDrawable tg = new GradientDrawable();
                tg.setColor(0x99000000);
                tg.setCornerRadius(dp(8));
                t.setBackground(tg);
                FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(-2, -2);
                tlp.gravity = Gravity.BOTTOM | Gravity.END;
                tlp.setMargins(0, 0, dp(6), dp(6));
                t.setLayoutParams(tlp);
                frame.addView(t);
                frame.setOnClickListener(v -> {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    try { act.startActivity(i); } catch (Exception e) { toast("لا يمكن فتح الصورة"); }
                });
                ((LinearLayout) v("messagesArea")).addView(frame);
            }

            // رسالة صوتية
            void addAudioMsg(String url, long durationMs, boolean mine, String time) {
                LinearLayout box = new LinearLayout(act);
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                box.setPadding(dp(10), dp(8), dp(14), dp(8));
                box.setTag(mine ? "bubMine" : "bubOther");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.gravity = mine ? Gravity.END : Gravity.START;
                lp.setMargins(0, dp(3), 0, dp(3));
                box.setLayoutParams(lp);
                box.setMinimumWidth(dp(180));

                TextView play = new TextView(act);
                play.setText("▶");
                play.setTextSize(18);
                play.setGravity(Gravity.CENTER);
                play.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
                play.setTag(mine ? "bubMine" : "bubOther");

                LinearLayout center = new LinearLayout(act);
                center.setOrientation(LinearLayout.VERTICAL);
                center.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1f));
                center.setPadding(dp(10), 0, dp(10), 0);

                TextView durTv = new TextView(act);
                durTv.setText(formatDuration(durationMs));
                durTv.setTextSize(fontSp);
                durTv.setTag(mine ? "bubMine" : "bubOther");
                durTv.setTextColor(mine ? (dark ? 0xFFFFFFFF : 0xFF17212B) : cText());

                TextView timeTv = new TextView(act);
                timeTv.setText(time);
                timeTv.setTextSize(10);
                timeTv.setTextColor(0xFF8696A7);

                center.addView(durTv); center.addView(timeTv);

                TextView wave = new TextView(act);
                wave.setText("▁▃▂▅▇▆▃▂");
                wave.setTextSize(14);
                wave.setTextColor(mine ? (dark ? 0xFFB0C4DE : 0xFF6B8E23) : cGold());
                box.addView(play); box.addView(center); box.addView(wave);

                play.setOnClickListener(v -> playAudio(url, play));
                box.setOnClickListener(v -> playAudio(url, play));

                ((LinearLayout) v("messagesArea")).addView(box);
                style(box);
            }

            String formatDuration(long ms) {
                long s = ms / 1000;
                return String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60);
            }

            void playAudio(String url, TextView btn) {
                try {
                    if (mediaPlayer != null) {
                        mediaPlayer.release();
                        mediaPlayer = null;
                    }
                    mediaPlayer = new MediaPlayer();
                    mediaPlayer.setAudioStreamType(android.media.AudioManager.STREAM_MUSIC);
                    mediaPlayer.setDataSource(url);
                    mediaPlayer.prepareAsync();
                    mediaPlayer.setOnPreparedListener(mp -> { mp.start(); btn.setText("⏸"); });
                    mediaPlayer.setOnCompletionListener(mp -> { btn.setText("▶"); });
                } catch (Exception e) {
                    toast("فشل تشغيل الصوت");
                }
            }

            void loadImageAsync(final ImageView img, final String url) {
                new Thread(() -> {
                    try {
                        java.net.URL u = new java.net.URL(url);
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) u.openConnection();
                        conn.setDoInput(true);
                        conn.connect();
                        java.io.InputStream is = conn.getInputStream();
                        BitmapFactory.Options opts = new BitmapFactory.Options();
                        opts.inSampleSize = 2;
                        final Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                        is.close();
                        if (bmp != null) act.runOnUiThread(() -> img.setImageBitmap(bmp));
                    } catch (Exception e) { }
                }).start();
            }

            void msgMenu(final TextView b, final boolean mine) {
                PopupMenu pm = new PopupMenu(act, b);
                pm.getMenu().add(0, 1, 0, "رد");
                pm.getMenu().add(0, 2, 1, "نسخ");
                if (mine) pm.getMenu().add(0, 3, 2, "تعديل");
                pm.getMenu().add(0, 4, 3, "حذف");
                pm.setOnMenuItemClickListener(it -> {
                    String raw = String.valueOf(b.getContentDescription());
                    switch (it.getItemId()) {
                        case 1:
                            replyText = raw.length() > 40 ? raw.substring(0, 40) + "…" : raw;
                            setLabel("replyLabel", "الرد على الرسالة", icon("ic_reply", 16));
                            tv("replyPreviewText").setText(replyText);
                            vis("replyPreviewBar", true);
                            et("messageInput").requestFocus();
                            break;
                        case 2:
                            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("msg", raw));
                            toast("تم نسخ الرسالة");
                            break;
                        case 3:
                            editing = b; replyText = null;
                            setLabel("replyLabel", "تعديل الرسالة", icon("ic_edit", 16));
                            tv("replyPreviewText").setText(raw);
                            vis("replyPreviewBar", true);
                            et("messageInput").setText(raw);
                            et("messageInput").setSelection(raw.length());
                            et("messageInput").requestFocus();
                            break;
                        case 4:
                            ((LinearLayout) v("messagesArea")).removeView(b);
                            break;
                    }
                    return true;
                });
                pm.show();
            }

            void cancelReply() {
                replyText = null; editing = null;
                vis("replyPreviewBar", false);
            }
            void scrollDown() {
                v("messagesScroll").post(() -> ((ScrollView) v("messagesScroll")).fullScroll(View.FOCUS_DOWN));
            }

            // ============ إرسال النصوص والوسائط ============
            void send() {
                String t = txt("messageInput");
                if (t.length() == 0) return;
                if (blocked) { toast("هذا المستخدم محظور"); return; }
                if (currentChatId.isEmpty() || otherUid.isEmpty()) return;

                String fullText = replyText != null ? "↩ " + replyText + "\n" + t : t;

                if (editing != null) {
                    setMsg(editing, t, true, now() + " (معدّلة)");
                    et("messageInput").setText("");
                    cancelReply();
                    return;
                }

                DatabaseReference ref = rtdb.getReference("messages").child(currentChatId).push();
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", "text");
                msg.put("text", fullText);
                msg.put("sender", myUid);
                msg.put("timestamp", System.currentTimeMillis());
                ref.setValue(msg);

                Map<String, Object> chatDoc = new HashMap<>();
                chatDoc.put("participants", Arrays.asList(myUid, otherUid));
                chatDoc.put("lastMessage", fullText);
                chatDoc.put("lastMessageAt", System.currentTimeMillis());
                chatDoc.put("lastSender", myUid);
                db.collection("chats").document(currentChatId).set(chatDoc);

                et("messageInput").setText("");
                cancelReply();
            }

            void sendMedia(String type, String url, long duration) {
                if (currentChatId.isEmpty() || otherUid.isEmpty()) return;
                DatabaseReference ref = rtdb.getReference("messages").child(currentChatId).push();
                Map<String, Object> msg = new HashMap<>();
                msg.put("type", type);
                msg.put("url", url);
                msg.put("duration", duration);
                msg.put("sender", myUid);
                msg.put("timestamp", System.currentTimeMillis());
                ref.setValue(msg);

                String preview = type.equals("image") ? "📷 صورة"
                        : type.equals("audio") ? "🎤 رسالة صوتية" : "";
                Map<String, Object> chatDoc = new HashMap<>();
                chatDoc.put("participants", Arrays.asList(myUid, otherUid));
                chatDoc.put("lastMessage", preview);
                chatDoc.put("lastMessageAt", System.currentTimeMillis());
                chatDoc.put("lastSender", myUid);
                db.collection("chats").document(currentChatId).set(chatDoc);
            }

            // ============ اختيار صورة ورفعها ============
            void pickImage() {
                pendingImageCallback = uri -> uploadImage(uri);
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                try {
                    act.startActivityForResult(Intent.createChooser(i, "اختر صورة"), REQ_PICK_IMAGE);
                } catch (Exception e) { toast("لا توجد تطبيقات لعرض الصور"); }
            }

            void uploadImage(Uri uri) {
                if (currentChatId.isEmpty()) return;
                toast("جاري رفع الصورة...");
                StorageReference ref = FirebaseStorage.getInstance().getReference()
                        .child("chats/" + currentChatId + "/img_" + System.currentTimeMillis() + ".jpg");
                ref.putFile(uri)
                    .addOnSuccessListener(ts -> ref.getDownloadUrl()
                        .addOnSuccessListener(u -> sendMedia("image", u.toString(), 0))
                        .addOnFailureListener(e -> toast("فشل رابط الرفع: " + e.getMessage())))
                    .addOnFailureListener(e -> toast("فشل الرفع: " + e.getMessage()));
            }

            // ============ تسجيل الصوت وإرساله ============
            void toggleRecording() {
                if (!isRecording) startRecord();
                else stopRecordAndSend();
            }

            void startRecord() {
                if (act.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    pendingMicCallback = this::startRecord;
                    act.requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC_PERM);
                    return;
                }
                try {
                    File dir = new File(act.getFilesDir(), "voice");
                    if (!dir.exists()) dir.mkdirs();
                    audioFile = new File(dir, "rec_" + System.currentTimeMillis() + ".m4a");

                    if (Build.VERSION.SDK_INT >= 31) recorder = new MediaRecorder(act);
                    else recorder = new MediaRecorder();
                    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                    recorder.setAudioEncodingBitRate(64000);
                    recorder.setAudioSamplingRate(44100);
                    recorder.setOutputFile(audioFile.getAbsolutePath());
                    recorder.prepare();
                    recorder.start();
                    isRecording = true;
                    recordStart = System.currentTimeMillis();

                    vis("recordingBar", true);
                    tv("recTimer").setText("0:00");
                    recTick = new Runnable() {
                        @Override public void run() {
                            if (!isRecording) return;
                            long s = (System.currentTimeMillis() - recordStart) / 1000;
                            tv("recTimer").setText(String.format(Locale.getDefault(), "%d:%02d", s / 60, s % 60));
                            recHandler.postDelayed(this, 500);
                        }
                    };
                    recHandler.post(recTick);
                    tv("btnMic").setTextColor(cRed());
                    tintIcons(tv("btnMic"));
                } catch (Exception e) {
                    toast("فشل بدء التسجيل: " + e.getMessage());
                    recorder = null;
                    isRecording = false;
                }
            }

            void stopRecordAndSend() {
                if (recorder == null) return;
                long dur = System.currentTimeMillis() - recordStart;
                try { recorder.stop(); } catch (Exception e) { }
                try { recorder.release(); } catch (Exception e) { }
                recorder = null;
                isRecording = false;
                if (recTick != null) recHandler.removeCallbacks(recTick);
                vis("recordingBar", false);
                tv("btnMic").setTextColor(cMuted());
                tintIcons(tv("btnMic"));

                if (audioFile == null || !audioFile.exists()) return;
                if (dur < 800) { audioFile.delete(); toast("التسجيل قصير جداً"); return; }
                uploadAudio(audioFile, dur);
            }

            void cancelRecord() {
                if (recorder == null) return;
                try { recorder.stop(); } catch (Exception e) { }
                try { recorder.release(); } catch (Exception e) { }
                recorder = null;
                isRecording = false;
                if (recTick != null) recHandler.removeCallbacks(recTick);
                vis("recordingBar", false);
                tv("btnMic").setTextColor(cMuted());
                tintIcons(tv("btnMic"));
                if (audioFile != null) audioFile.delete();
                toast("تم إلغاء التسجيل");
            }

            void uploadAudio(File f, long dur) {
                if (currentChatId.isEmpty()) return;
                toast("جاري رفع الصوت...");
                StorageReference ref = FirebaseStorage.getInstance().getReference()
                        .child("chats/" + currentChatId + "/voice_" + System.currentTimeMillis() + ".m4a");
                ref.putFile(Uri.fromFile(f))
                    .addOnSuccessListener(ts -> ref.getDownloadUrl()
                        .addOnSuccessListener(u -> { sendMedia("audio", u.toString(), dur); f.delete(); })
                        .addOnFailureListener(e -> toast("فشل: " + e.getMessage())))
                    .addOnFailureListener(e -> toast("فشل الرفع: " + e.getMessage()));
            }

            void chatMenu() {
                PopupMenu pm = new PopupMenu(act, v("chatMore"));
                pm.getMenu().add(0, 1, 0, muted ? "إلغاء كتم المحادثة" : "كتم المحادثة");
                pm.getMenu().add(0, 2, 1, "البحث عن رسالة");
                pm.getMenu().add(0, 3, 2, "مسح المحادثة");
                pm.setOnMenuItemClickListener(it -> {
                    if (it.getItemId() == 1) { muted = !muted; toast(muted ? "تم كتم المحادثة" : "تم إلغاء الكتم"); }
                    else if (it.getItemId() == 2) { vis("chatSearchPanel", true); et("chatMessageSearchInput").requestFocus(); }
                    else {
                        if (!currentChatId.isEmpty()) rtdb.getReference("messages").child(currentChatId).removeValue();
                        ((LinearLayout) v("messagesArea")).removeAllViews();
                        toast("تم مسح المحادثة");
                    }
                    return true;
                });
                pm.show();
            }

            void searchInChat(String q) {
                LinearLayout a = (LinearLayout) v("messagesArea");
                q = q.trim().toLowerCase();
                int found = 0;
                View first = null;
                for (int i = 0; i < a.getChildCount(); i++) {
                    View c = a.getChildAt(i);
                    boolean hit = q.length() > 0 && String.valueOf(c.getContentDescription()).toLowerCase().contains(q);
                    c.setAlpha(q.length() == 0 || hit ? 1f : 0.3f);
                    if (hit) { found++; if (first == null) first = c; }
                }
                tv("csCount").setText(q.length() == 0 ? "" : (found + " نتيجة"));
                if (first != null) ((ScrollView) v("messagesScroll")).smoothScrollTo(0, first.getTop());
            }

            void searchUsers(String q) {
                String cleanQ = q.replace("@", "").trim().toLowerCase();
                LinearLayout res = (LinearLayout) v("searchResults");
                res.removeAllViews();
                if (cleanQ.length() < 3) { tv("searchHint").setText("اكتب 3 أحرف على الأقل"); return; }
                db.collection("users").orderBy("username").startAt(cleanQ).endAt(cleanQ + "\uf8ff")
                    .get().addOnCompleteListener(task -> {
                        res.removeAllViews();
                        int n = 0;
                        if (task.isSuccessful() && task.getResult() != null) {
                            for (QueryDocumentSnapshot d : task.getResult()) {
                                String uid = d.getId();
                                if (uid.equals(myUid)) continue;
                                String name = d.getString("name");
                                String username = d.getString("username");
                                if (name == null) name = "?";
                                if (username == null) username = "";
                                n++;
                                final String fName = name, fUser = username, fUid = uid;
                                LinearLayout row = new LinearLayout(act);
                                row.setGravity(Gravity.CENTER_VERTICAL);
                                row.setPadding(dp(14), dp(10), dp(14), dp(10));
                                TextView av = new TextView(act);
                                av.setText(name.length() > 0 ? name.substring(0, 1) : "?");
                                av.setGravity(Gravity.CENTER);
                                av.setTextSize(18);
                                av.setTextColor(0xFF17212B);
                                av.setTag("circ");
                                av.setLayoutParams(new LinearLayout.LayoutParams(dp(46), dp(46)));
                                LinearLayout col = new LinearLayout(act);
                                col.setOrientation(LinearLayout.VERTICAL);
                                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
                                cp.setMargins(dp(12), 0, dp(12), 0);
                                col.setLayoutParams(cp);
                                TextView a = new TextView(act);
                                a.setText(name); a.setTextSize(15); a.setTypeface(null, 1); a.setTag("t");
                                TextView b = new TextView(act);
                                b.setText("@" + username); b.setTextSize(12); b.setTag("m");
                                col.addView(a); col.addView(b);
                                row.addView(av); row.addView(col);
                                row.setOnClickListener(x -> openOther(fName, fUser, fUid));
                                res.addView(row);
                                style(row);
                            }
                        }
                        tv("searchHint").setText(n == 0 ? "لا توجد نتائج" : n + " نتيجة");
                    });
            }

            void buildThemeStrip() {
                LinearLayout s = (LinearLayout) v("themeStrip");
                s.removeAllViews();
                for (int i = 0; i < themeNames.length; i++) {
                    final int idx = i;
                    TextView c = new TextView(act);
                    c.setText(themeNames[i]);
                    c.setTextSize(13);
                    c.setGravity(Gravity.CENTER);
                    c.setPadding(dp(16), dp(8), dp(16), dp(8));
                    c.setTag(i == chatBgIdx ? "gold" : "goldo");
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
                    p.setMargins(0, 0, dp(8), 0);
                    c.setLayoutParams(p);
                    c.setOnClickListener(x -> {
                        chatBgIdx = idx;
                        sp.edit().putInt("chatBg", idx).apply();
                        applyTheme();
                    });
                    s.addView(c);
                    style(c);
                }
            }

            void applyBubbles() {
                tv("pvOther").setTextSize(fontSp);
                tv("pvMine").setTextSize(fontSp);
                LinearLayout a = (LinearLayout) v("messagesArea");
                for (int i = 0; i < a.getChildCount(); i++) {
                    View c = a.getChildAt(i);
                    if (c instanceof TextView) ((TextView) c).setTextSize(fontSp);
                }
                style(v("pvBox"));
                style(a);
            }

            void setSeg(String id, boolean hidden) {
                setOn(v(id + "_show"), !hidden, "goldo");
                setOn(v(id + "_hide"), hidden, "goldo");
            }
            void setOnOff(String id, boolean on) {
                setOn(v(id + "OnBtn"), on, "goldo");
                setOn(v(id + "OffBtn"), !on, "goldo");
            }
            void confirm(String title, String msg, final Runnable ok) {
                new AlertDialog.Builder(act).setTitle(title).setMessage(msg)
                    .setPositiveButton("تأكيد", (d, w) -> ok.run())
                    .setNegativeButton("إلغاء", null).show();
            }
            void logout() {
                mAuth.signOut();
                sp.edit().putBoolean("logged", false).apply();
                startActivity(new Intent(act, Main2Activity.class));
                act.finish();
            }
            void input(String title, String hint, final String key, final String okMsg) {
                final EditText e = new EditText(act);
                e.setHint(hint);
                new AlertDialog.Builder(act).setTitle(title).setView(e)
                    .setPositiveButton("حفظ", (d, w) -> {
                        String s = e.getText().toString().trim();
                        if (s.length() > 0) {
                            Map<String, Object> upd = new HashMap<>();
                            upd.put(key, s);
                            db.collection("users").document(myUid).update(upd)
                                .addOnSuccessListener(a -> { loadMyProfile(); toast(okMsg); })
                                .addOnFailureListener(er -> toast("فشل: " + er.getMessage()));
                        }
                    }).setNegativeButton("إلغاء", null).show();
            }

            // ============ معالج النقرات ============
            void click(String id) {
                if (id.equals("hdrStories")) toast("القصص");
                else if (id.equals("mainSearchBtn")) { et("searchInput").setText(""); show("searchView"); et("searchInput").requestFocus(); }
                else if (id.equals("searchBack") || id.equals("navChats")) show("homeView");
                else if (id.equals("navSettings")) show("settingsView");
                else if (id.equals("navProfile") || id.equals("sxHero")) { loadMyProfile(); show("profileView"); }
                else if (id.equals("fltAll")) setFilter(false);
                else if (id.equals("fltUnread")) setFilter(true);
                else if (id.equals("newGroupFab")) {
                    final EditText e = new EditText(act);
                    e.setHint("اسم المجموعة");
                    new AlertDialog.Builder(act).setTitle("مجموعة جديدة").setView(e)
                        .setPositiveButton("إنشاء", (d, w) -> toast("إنشاء المجموعات قيد التطوير"))
                        .setNegativeButton("إلغاء", null).show();
                }
                // profile
                else if (id.equals("pEdit") || id.equals("pBioRow")) openEdit();
                else if (id.equals("pPhoto")) toast("اختيار صورة (يتطلب Firebase Storage)");
                else if (id.equals("pCover")) {
                    new AlertDialog.Builder(act).setTitle("لون الغلاف")
                        .setItems(new String[]{"كحلي", "بنفسجي", "أخضر", "برتقالي", "رمادي", "ذهبي"}, (d, w) -> {
                            coverColor = covers[w];
                            sp.edit().putInt("cover", coverColor).apply();
                            ((View) v("pCoverTxt").getParent()).setBackgroundColor(coverColor);
                        }).show();
                }
                else if (id.equals("pUserRow")) {
                    ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("u", tv("pHeaderUser").getText().toString()));
                    toast("تم نسخ اسم المستخدم");
                }
                else if (id.equals("editBack") || id.equals("btnCancelEdit")) show("profileView");
                else if (id.equals("btnSaveProfile")) saveProfile();
                // other profile (بدون نجوم وهدايا)
                else if (id.equals("otherBack")) show(prevPage);
                else if (id.equals("otherMsgBtn")) openChat(otherName, otherUid);
                else if (id.equals("otherBlockBtn")) {
                    blocked = !blocked;
                    setLabel("otherBlockBtn", blocked ? "إلغاء الحظر" : "حظر", icon(blocked ? "ic_check" : "ic_block", 16));
                    toast(blocked ? "تم حظر " + otherName : "تم إلغاء الحظر");
                }
                else if (id.equals("otherMoreBtn")) {
                    PopupMenu pm = new PopupMenu(act, v("otherMoreBtn"));
                    pm.getMenu().add(0, 1, 0, "نسخ اسم المستخدم");
                    pm.getMenu().add(0, 2, 1, "إبلاغ");
                    pm.setOnMenuItemClickListener(it -> {
                        if (it.getItemId() == 1) {
                            ClipboardManager cm = (ClipboardManager) act.getSystemService(Context.CLIPBOARD_SERVICE);
                            cm.setPrimaryClip(ClipData.newPlainText("u", "@" + otherUser));
                            toast("تم النسخ");
                        } else toast("تم إرسال البلاغ");
                        return true;
                    });
                    pm.show();
                }
                // settings
                else if (id.equals("settingsThemeToggle")) {
                    dark = !dark;
                    sp.edit().putBoolean("dark", dark).apply();
                    applyTheme();
                }
                else if (id.equals("itemChatSettings")) show("chatSettingsView");
                else if (id.equals("itemNotif")) show("notificationSettingsView");
                else if (id.equals("itemPrivacy")) show("privacySettingsView");
                else if (id.equals("privacyBack") || id.equals("notifBack") || id.equals("chatSetBack")) show("settingsView");
                else if (id.equals("itemShare")) {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, "حمّل تطبيق Babylon Chat");
                    act.startActivity(Intent.createChooser(i, "مشاركة رابط التحميل"));
                }
                else if (id.equals("itemLogout")) confirm("تسجيل الخروج", "هل تريد تسجيل الخروج؟", this::logout);
                else if (id.equals("itemDelete")) confirm("حذف الحساب", "سيتم حذف حسابك وبياناتك نهائياً. متأكد؟", () -> {
                    if (currentUser != null) {
                        db.collection("users").document(myUid).delete();
                        currentUser.delete().addOnCompleteListener(t -> logout());
                    }
                });
                // privacy
                else if (id.equals("itemChangePass")) {
                    if (currentUser != null && currentUser.getEmail() != null) {
                        mAuth.sendPasswordResetEmail(currentUser.getEmail())
                            .addOnSuccessListener(a -> toast("سيتم إرسال رابط تغيير كلمة المرور لبريدك"))
                            .addOnFailureListener(e -> toast("فشل: " + e.getMessage()));
                    }
                }
                else if (id.equals("itemChangeEmail")) input("تغيير الإيميل", "name@domain.com", "email", "تم تحديث الإيميل");
                else if (id.equals("itemBlocked")) toast("لا يوجد مستخدمون محظورون");
                else if (id.equals("segLastSeen_show")) { db.collection("users").document(myUid).update("hideLastSeen", false); setSeg("segLastSeen", false); }
                else if (id.equals("segLastSeen_hide")) { db.collection("users").document(myUid).update("hideLastSeen", true); setSeg("segLastSeen", true); }
                else if (id.equals("segAvatar_show")) { db.collection("users").document(myUid).update("hideAvatar", false); setSeg("segAvatar", false); }
                else if (id.equals("segAvatar_hide")) { db.collection("users").document(myUid).update("hideAvatar", true); setSeg("segAvatar", true); }
                else if (id.equals("itemClearCache")) toast("تم مسح البيانات المؤقتة");
                else if (id.equals("itemWipe")) confirm("مسح جميع البيانات", "سيتم مسح كل بيانات التطبيق المحلية.", () -> {
                    sp.edit().clear().apply();
                    toast("تم المسح");
                    logout();
                });
                else if (id.equals("itemAbout")) new AlertDialog.Builder(act).setTitle("حول Babylon Chat")
                    .setMessage("تطبيق المحادثات العربي\nمحادثات، مكالمات، قصص.")
                    .setPositiveButton("حسناً", null).show();
                // notif
                else if (id.equals("pushOnBtn")) { sp.edit().putBoolean("push", true).apply(); setOnOff("push", true); }
                else if (id.equals("pushOffBtn")) { sp.edit().putBoolean("push", false).apply(); setOnOff("push", false); }
                else if (id.equals("soundOnBtn")) { sp.edit().putBoolean("sound", true).apply(); setOnOff("sound", true); }
                else if (id.equals("soundOffBtn")) { sp.edit().putBoolean("sound", false).apply(); setOnOff("sound", false); }
                // chat
                else if (id.equals("chatBack")) closeChat();
                else if (id.equals("chatUserInfo")) { hideKb(); vis("chatView", false); openOther(curChat, otherUser, otherUid); }
                else if (id.equals("chatMore")) chatMenu();
                else if (id.equals("csX")) { vis("chatSearchPanel", false); et("chatMessageSearchInput").setText(""); searchInChat(""); }
                else if (id.equals("replyCancel")) { cancelReply(); et("messageInput").setText(""); }
                else if (id.equals("btnSend")) send();
                else if (id.equals("btnMic")) toggleRecording();
                else if (id.equals("recSend")) stopRecordAndSend();
                else if (id.equals("recCancel")) cancelRecord();
                else if (id.equals("btnAttach")) {
                    new AlertDialog.Builder(act).setTitle("إرفاق")
                        .setItems(new String[]{"صورة من المعرض", "ملف"}, (d, w) -> {
                            if (w == 0) pickImage();
                            else toast("الملفات قريباً");
                        }).show();
                }
            }

            // ============ التهيئة ============
            void init() {
                v("root").setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

                FrameLayout host = (FrameLayout) v("pagesHost");
                String[] partFiles = {"part_profile", "part_settings"};
                for (String pf : partFiles) {
                    int lid = act.getResources().getIdentifier(pf, "layout", act.getPackageName());
                    ViewGroup pr = (ViewGroup) act.getLayoutInflater().inflate(lid, null);
                    while (pr.getChildCount() > 0) {
                        View c = pr.getChildAt(0);
                        pr.removeViewAt(0);
                        host.addView(c);
                    }
                }

                Spinner sg = (Spinner) v("modalGovInput");
                sg.setAdapter(new ArrayAdapter<String>(act, android.R.layout.simple_spinner_dropdown_item, govs) {
                    @Override
                    public View getView(int pos, View cv, ViewGroup parent) {
                        TextView t = (TextView) super.getView(pos, cv, parent);
                        t.setTextColor(cText());
                        return t;
                    }
                });

                View.OnClickListener L = x -> click(act.getResources().getResourceEntryName(x.getId()));
                String[] ids = {"hdrStories", "mainSearchBtn", "searchBack", "fltAll", "fltUnread",
                        "newGroupFab", "navChats", "navSettings", "navProfile", "pEdit", "pPhoto", "pCover",
                        "pBioRow", "pUserRow", "editBack", "btnCancelEdit", "btnSaveProfile", "otherBack",
                        "otherMsgBtn", "otherBlockBtn", "otherMoreBtn", "settingsThemeToggle", "sxHero",
                        "itemChatSettings", "itemNotif", "itemPrivacy", "privacyBack", "notifBack",
                        "chatSetBack", "itemShare", "itemLogout", "itemDelete", "itemChangePass",
                        "itemChangeEmail", "itemBlocked", "segLastSeen_show", "segLastSeen_hide",
                        "segAvatar_show", "segAvatar_hide", "itemClearCache", "itemWipe", "itemAbout",
                        "pushOnBtn", "pushOffBtn", "soundOnBtn", "soundOffBtn", "chatBack", "chatUserInfo",
                        "chatMore", "csX", "replyCancel", "btnSend", "btnMic", "btnAttach", "recSend", "recCancel"};
                for (String id : ids) {
                    View x = v(id);
                    if (x != null) x.setOnClickListener(L);
                }

                et("searchInput").addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    public void onTextChanged(CharSequence s, int a, int b, int c) { searchUsers(s.toString()); }
                    public void afterTextChanged(Editable s) { }
                });
                et("chatMessageSearchInput").addTextChangedListener(new TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
                    public void onTextChanged(CharSequence s, int a, int b, int c) { searchInChat(s.toString()); }
                    public void afterTextChanged(Editable s) { }
                });

                SeekBar fr = (SeekBar) v("fontRange"), rr = (SeekBar) v("radiusRange");
                fr.setProgress(fontSp - 12);
                rr.setProgress(radius);
                tv("fontVal").setText("" + fontSp);
                tv("radiusVal").setText("" + radius);
                fr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar s, int p, boolean u) {
                        fontSp = 12 + p;
                        tv("fontVal").setText("" + fontSp);
                        sp.edit().putInt("font", fontSp).apply();
                        applyBubbles();
                    }
                    public void onStartTrackingTouch(SeekBar s) { }
                    public void onStopTrackingTouch(SeekBar s) { }
                });
                rr.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar s, int p, boolean u) {
                        radius = p;
                        tv("radiusVal").setText("" + p);
                        sp.edit().putInt("radius", p).apply();
                        applyBubbles();
                    }
                    public void onStartTrackingTouch(SeekBar s) { }
                    public void onStopTrackingTouch(SeekBar s) { }
                });

                setSeg("segLastSeen", sp.getBoolean("hideLast", false));
                setSeg("segAvatar", sp.getBoolean("hideAv", false));
                setOnOff("push", sp.getBoolean("push", true));
                setOnOff("sound", sp.getBoolean("sound", true));
                applyBubbles();
                applyTheme();

                loadMyProfile();
                loadChats();
                show("homeView");
            }
        }

        UI ui = new UI();
        ui.init();

        // ======= شريط حالة وتنقّل شفافان مع Insets =======
        Window w = getWindow();
        w.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 29) {
            w.setNavigationBarContrastEnforced(false);
            w.setStatusBarContrastEnforced(false);
        }
        boolean darkNow = getSharedPreferences("babylon_ui", MODE_PRIVATE).getBoolean("dark", false);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 23 && !darkNow) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26 && !darkNow) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        w.getDecorView().setSystemUiVisibility(flags);

        final View rootView = findViewById(R.id.root);
        rootView.setOnApplyWindowInsetsListener((v, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            v.setPadding(0, top, 0, bottom);
            return insets;
        });
        rootView.requestApplyInsets();
    }
}