# إعداد شاشة التحقق من البريد (رمز 6 أرقام)

الواجهة والمنطق داخل التطبيق جاهزان. إرسال الرمز نفسه يحتاج خادماً (Firebase لا يرسل رمز أرقام من التطبيق مباشرة):

1. ثبّت الأدوات:  `npm i -g firebase-tools`  ثم  `firebase login`
2. انسخ `functions/.env.example` إلى `functions/.env` واملأ بيانات SMTP (يُفضّل Gmail + App Password).
3. من جذر المشروع:  `firebase init functions` (اختر المشروع نفسه، واحتفظ بملفات functions الموجودة) ثم  `firebase deploy --only functions`
4. أضف هذه القاعدة إلى Firestore Rules كي لا يقرأ التطبيق الرموز:
   ```
   match /emailOtps/{id} { allow read, write: if false; }
   ```
   ولمنع المستخدم من جعل `emailVerified` صحيحاً بنفسه، امنع تعديل هذا الحقل من العميل في قاعدة `users/{uid}`
   (مثلاً: `allow update: if !request.resource.data.diff(resource.data).affectedKeys().hasAny(['emailVerified']);`)
5. ملاحظة: خطة Firebase Blaze (الدفع حسب الاستخدام) مطلوبة لنشر Cloud Functions؛ الاستهلاك الصغير يبقى ضمن الحصة المجانية.

الحسابات القديمة التي لا تحتوي حقل `emailVerified` تدخل كما هي بلا طلب رمز.
