package com.babylon.chat.iq;

import android.content.Context;
import android.os.Build;

import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * جلسات الحساب: كل جهاز يسجّل نفسه في users/{uid}/sessions/{deviceId}.
 * الحد الأقصى MAX أجهزة نشطة معاً: عند دخول جهاز جديد والحد ممتلئ تُنهى أقدم جلسة (الأقل نشاطاً)
 * فيخرج ذلك الجهاز تلقائياً لأنه يراقب مستند جلسته. بلا Cloud Functions.
 */
final class Sessions {
    private Sessions() { }

    static final int MAX = 3;
    private static final String PREFS = "babylon_boot", KEY_ID = "device_id";
    private static final long TOUCH_EVERY = 5 * 60 * 1000L;
    private static long lastTouch = 0;

    static final class Row {
        String id, name, platform;
        long createdAt, lastActive;
        boolean current, admin;   // admin: الجلسة الأقدم (الجهاز الرئيسي) وحدها تنهي الجلسات الأخرى
    }

    interface ListCb { void done(List<Row> rows); }
    interface DoneCb { void done(boolean ok); }

    static String deviceId(Context c) {
        try {
            android.content.SharedPreferences sp = AppSettings.prefs(c.getApplicationContext(), PREFS);
            String id = sp.getString(KEY_ID, null);
            if (id == null || id.isEmpty()) { id = UUID.randomUUID().toString(); sp.edit().putString(KEY_ID, id).apply(); }
            return id;
        } catch (Throwable t) { return "unknown"; }
    }

    static String deviceName() {
        String m = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        String d = Build.MODEL == null ? "" : Build.MODEL.trim();
        if (!m.isEmpty() && d.toLowerCase().startsWith(m.toLowerCase())) return d;
        String s = (m.isEmpty() ? "" : Character.toUpperCase(m.charAt(0)) + m.substring(1) + " ") + d;
        return s.trim().isEmpty() ? "جهاز أندرويد" : s.trim();
    }

    private static CollectionReference col(String uid) {
        return FirebaseFirestore.getInstance().collection("users").document(uid).collection("sessions");
    }

    /** يسجّل هذا الجهاز؛ إن امتلأ الحد تُحذف أقدم الجلسات الأخرى لتبقى MAX-1 قبل الإضافة. */
    static void register(final Context c, final String uid, final DoneCb cb) {
        final String me = deviceId(c);
        col(uid).get().addOnSuccessListener((QuerySnapshot qs) -> {
            try {
                WriteBatch wb = FirebaseFirestore.getInstance().batch();
                boolean exists = false;
                List<DocumentSnapshot> others = new ArrayList<>();
                for (DocumentSnapshot d : qs.getDocuments()) {
                    if (me.equals(d.getId())) exists = true; else others.add(d);
                }
                Map<String, Object> data = new HashMap<>();
                data.put("name", deviceName());
                data.put("platform", "Android " + Build.VERSION.RELEASE);
                data.put("lastActive", System.currentTimeMillis());
                if (!exists) {
                    data.put("createdAt", System.currentTimeMillis());
                    // الجلسة الأقدم (الرئيسية) محميّة من الإنهاء التلقائي؛ تُنهى الأقل نشاطاً من بين الباقي
                    DocumentSnapshot adminDoc = null;
                    for (DocumentSnapshot d : others) if (adminDoc == null || older(d, adminDoc)) adminDoc = d;
                    List<DocumentSnapshot> removable = new ArrayList<>(others);
                    if (adminDoc != null) removable.remove(adminDoc);
                    Collections.sort(removable, new Comparator<DocumentSnapshot>() {
                        @Override public int compare(DocumentSnapshot a, DocumentSnapshot b) { return Long.compare(num(a, "lastActive"), num(b, "lastActive")); }
                    });
                    int toRemove = others.size() - (MAX - 1);
                    for (int i = 0; i < toRemove && i < removable.size(); i++) wb.delete(removable.get(i).getReference());
                }
                wb.set(col(uid).document(me), data, SetOptions.merge());
                wb.commit().addOnCompleteListener(t -> { lastTouch = System.currentTimeMillis(); if (cb != null) cb.done(t.isSuccessful()); });
            } catch (Throwable t) { if (cb != null) cb.done(false); }
        }).addOnFailureListener(e -> { if (cb != null) cb.done(false); });
    }

    /** تحديث آخر نشاط (لا أكثر من مرة كل 5 دقائق). */
    static void touch(Context c, String uid) {
        long n = System.currentTimeMillis();
        if (uid == null || uid.isEmpty() || n - lastTouch < TOUCH_EVERY) return;
        lastTouch = n;
        try { col(uid).document(deviceId(c)).update("lastActive", n); } catch (Throwable ignored) { }
    }

    /** يراقب مستند جلسة هذا الجهاز: إن حُذف من جهاز آخر (أو بسبب الحد) يُستدعى onEnded مرة واحدة. */
    static ListenerRegistration watchSelf(Context c, String uid, final Runnable onEnded) {
        final boolean[] seen = {false};
        return col(uid).document(deviceId(c)).addSnapshotListener((snap, e) -> {
            if (snap == null || snap.getMetadata().isFromCache()) return;
            if (snap.exists()) { seen[0] = true; return; }
            if (seen[0]) { seen[0] = false; if (onEnded != null) onEnded.run(); }
        });
    }

    static void list(final Context c, String uid, final ListCb cb) {
        final String me = deviceId(c);
        col(uid).get().addOnSuccessListener(qs -> {
            List<Row> out = new ArrayList<>();
            for (DocumentSnapshot d : qs.getDocuments()) {
                Row r = new Row();
                r.id = d.getId();
                r.name = d.getString("name") == null ? "جهاز" : d.getString("name");
                r.platform = d.getString("platform") == null ? "" : d.getString("platform");
                r.createdAt = num(d, "createdAt");
                r.lastActive = num(d, "lastActive");
                r.current = me.equals(r.id);
                out.add(r);
            }
            Row adm = null;
            for (Row r : out) if (adm == null || r.createdAt < adm.createdAt || (r.createdAt == adm.createdAt && r.id.compareTo(adm.id) < 0)) adm = r;
            if (adm != null) adm.admin = true;
            Collections.sort(out, new Comparator<Row>() {
                @Override public int compare(Row a, Row b) {
                    if (a.current != b.current) return a.current ? -1 : 1;
                    return Long.compare(b.lastActive, a.lastActive);
                }
            });
            cb.done(out);
        }).addOnFailureListener(e -> cb.done(null));
    }

    static void end(String uid, String deviceId, final DoneCb cb) {
        col(uid).document(deviceId).delete().addOnCompleteListener(t -> { if (cb != null) cb.done(t.isSuccessful()); });
    }

    static void endOthers(final Context c, final String uid, final DoneCb cb) {
        final String me = deviceId(c);
        col(uid).get().addOnSuccessListener(qs -> {
            WriteBatch wb = FirebaseFirestore.getInstance().batch();
            for (DocumentSnapshot d : qs.getDocuments()) if (!me.equals(d.getId())) wb.delete(d.getReference());
            wb.commit().addOnCompleteListener(t -> { if (cb != null) cb.done(t.isSuccessful()); });
        }).addOnFailureListener(e -> { if (cb != null) cb.done(false); });
    }

    /** يحذف جلسة هذا الجهاز عند تسجيل الخروج. */
    static void removeSelf(Context c, String uid) {
        try { col(uid).document(deviceId(c)).delete(); } catch (Throwable ignored) { }
    }

    private static boolean older(DocumentSnapshot a, DocumentSnapshot b) {
        long x = num(a, "createdAt"), y = num(b, "createdAt");
        return x != y ? x < y : a.getId().compareTo(b.getId()) < 0;
    }

    private static long num(DocumentSnapshot d, String f) {
        try { Long v = d.getLong(f); return v == null ? 0 : v; } catch (Throwable t) { return 0; }
    }

    static String ago(long ts) {
        if (ts <= 0) return "—";
        long m = Math.max(0, (System.currentTimeMillis() - ts) / 60000L);
        if (m < 5) return "نشط الآن";
        if (m < 60) return "قبل " + m + " دقيقة";
        long h = m / 60;
        if (h < 24) return "قبل " + h + (h == 1 ? " ساعة" : " ساعات");
        long d = h / 24;
        return "قبل " + d + (d == 1 ? " يوم" : " أيام");
    }
}
