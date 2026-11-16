# التحجيم الديناميكي (220dp – 711dp)

كل شيء في التطبيق يتحجّم تلقائياً مع أقصر ضلع للشاشة: المعامل = clamp(shortestSideDp, 220, 711) / 360.
الآلية: `ScreenScale.wrap()` تضرب densityDpi بالمعامل فيتحجّم كل dp وsp (XML + Java + Canvas + Dialogs + Toasts).

## قواعد للكود الجديد
1. كل Activity جديدة: `extends ScaledActivity` (وتُسجَّل في الـManifest).
2. أي Service/سياق خارج الشاشات: `ScreenScale.wrap(base)` داخل `attachBaseContext`.
3. المقاسات دائماً بـdp/sp (XML) أو `ScreenScale.dp(ctx, v)` / `view.getResources().getDisplayMetrics().density` (Java) — لا بكسلات ثابتة.
4. ممنوع: `COMPLEX_UNIT_PX`، `Resources.getSystem()`، `getDefaultDisplay()` (تعيد الكثافة الحقيقية غير المحجَّمة). إن كان استعمالها مقصوداً أضف تعليق `// scale-ok`.
5. مهمة `checkDynamicScale` مربوطة بـpreBuild وتفشل البناء عند المخالفة.

ملاحظة: إشعارات النظام (Notification) يرسمها SystemUI خارج التطبيق فلا يمكن تحجيمها من الكود؛ التطبيق حالياً لا ينشئ إشعارات محلية.
