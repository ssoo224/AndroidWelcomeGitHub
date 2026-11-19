/**
 * رمز التحقق من البريد (6 أرقام) لتطبيق Babylon Chat.
 *  - sendEmailOtp  : يولّد رمزاً، يخزّن بصمته (hash) فقط، ويرسله لبريد المستخدم المسجَّل دخوله.
 *  - verifyEmailOtp: يفحص الرمز (5 محاولات كحدّ أقصى، صلاحية 10 دقائق) ثم يضع users/{uid}.emailVerified = true.
 */
const { onCall, HttpsError } = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");
const nodemailer = require("nodemailer");
const crypto = require("crypto");

admin.initializeApp();
const db = admin.firestore();

const TTL_MS = 10 * 60 * 1000;       // صلاحية الرمز
const RESEND_GAP_MS = 55 * 1000;     // أقل فاصل بين رمزين (التطبيق يعرض 60ث)
const MAX_ATTEMPTS = 5;

const hash = (uid, code) =>
  crypto.createHash("sha256").update(`${uid}:${code}:${process.env.OTP_PEPPER || ""}`).digest("hex");

function mailer() {
  return nodemailer.createTransport({
    host: process.env.SMTP_HOST,
    port: Number(process.env.SMTP_PORT || 465),
    secure: Number(process.env.SMTP_PORT || 465) === 465,
    auth: { user: process.env.SMTP_USER, pass: process.env.SMTP_PASS },
  });
}

const REGION = "us-central1";   // يجب أن يطابق منطقة FirebaseFunctions.getInstance() في التطبيق (الافتراضية us-central1)

exports.sendEmailOtp = onCall({ region: REGION }, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "login required");
  const uid = req.auth.uid;
  const email = req.auth.token.email;
  if (!email) throw new HttpsError("failed-precondition", "no email");

  const ref = db.collection("emailOtps").doc(uid);
  const snap = await ref.get();
  const now = Date.now();
  if (snap.exists && now - (snap.get("sentAt") || 0) < RESEND_GAP_MS) {
    throw new HttpsError("resource-exhausted", "too soon");
  }

  if (!process.env.SMTP_HOST || !process.env.SMTP_USER || !process.env.SMTP_PASS) {
    logger.error("SMTP غير مهيّأ: تأكد من وجود functions/.env ثم أعد النشر");
    throw new HttpsError("failed-precondition", "smtp not configured");
  }

  const code = String(crypto.randomInt(0, 1000000)).padStart(6, "0");
  await ref.set({ h: hash(uid, code), sentAt: now, expiresAt: now + TTL_MS, attempts: 0 });

  try {
    await mailer().sendMail({
      from: process.env.MAIL_FROM || process.env.SMTP_USER,
      to: email,
      subject: "رمز التحقق - Babylon Chat",
      text: `رمز التحقق الخاص بك: ${code}\nصالح لمدة 10 دقائق. إن لم تطلبه فتجاهل هذه الرسالة.`,
      html: `<div dir="rtl" style="font-family:Arial,sans-serif;text-align:center">
               <h2 style="color:#B8892A">Babylon Chat</h2>
               <p>رمز التحقق الخاص بك:</p>
               <p style="font-size:34px;letter-spacing:8px;font-weight:bold;color:#8F6C10">${code}</p>
               <p style="color:#667">صالح لمدة 10 دقائق. إن لم تطلبه فتجاهل هذه الرسالة.</p>
             </div>`,
    });
  } catch (e) {
    // فشل الإرسال: نحذف السجل كي لا يُمنع المستخدم من إعادة المحاولة فوراً
    logger.error("sendMail failed", { code: e.code, responseCode: e.responseCode, message: e.message });
    await ref.delete().catch(() => {});
    throw new HttpsError("internal", "mail send failed");
  }
  return { ok: true };
});

exports.verifyEmailOtp = onCall({ region: REGION }, async (req) => {
  if (!req.auth) throw new HttpsError("unauthenticated", "login required");
  const uid = req.auth.uid;
  const code = String((req.data && req.data.code) || "");
  if (!/^\d{6}$/.test(code)) throw new HttpsError("invalid-argument", "bad code");

  const ref = db.collection("emailOtps").doc(uid);
  const snap = await ref.get();
  if (!snap.exists) throw new HttpsError("deadline-exceeded", "no code");
  const d = snap.data();
  if (Date.now() > d.expiresAt) { await ref.delete(); throw new HttpsError("deadline-exceeded", "expired"); }
  if (d.attempts >= MAX_ATTEMPTS) throw new HttpsError("resource-exhausted", "too many attempts");

  const a = Buffer.from(hash(uid, code)), b = Buffer.from(d.h);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    await ref.update({ attempts: admin.firestore.FieldValue.increment(1) });
    throw new HttpsError("invalid-argument", "wrong code");
  }

  await ref.delete();
  await db.collection("users").doc(uid).set({ emailVerified: true }, { merge: true });
  await admin.auth().updateUser(uid, { emailVerified: true });
  return { ok: true };
});
