# Phase H — طراحی runtime و یکپارچه‌سازی مدل استنتاج

> **وضعیت:** سند طراحی؛ هیچ runtime، dependency، permission، UI چت یا کد استنتاجی در این فاز اضافه نشده است.
> **تاریخ بررسی:** ۱۳ اکتبر ۲۰۲۶.
> **Roadmap source:** فایل `MASTER-ROADMAP-v4-FINAL.md` در `/home/user/uploads/MASTER-ROADMAP-v4-FINAL.md` موجود است، اما بیرون از repository و خارج از Git است. گزیده‌های دقیق و شماره‌خط‌های آن در §۱ آمده‌اند. برای `GEN-310` فقط تعریف کوتاه خود roadmap نقل می‌شود؛ جزئیات عددی retry/backoff و mapping کامل خطا که در متن صریح نیست، اثبات‌نشده می‌ماند.

## ۱. هدف، مرزها و وضعیت فعلی

Phase H باید پیش از انتخاب یا افزودن موتور native، artifact مدل، یا مسیر دانلود، قراردادهای موجود برنامه را با محدودیت‌های واقعی هر گزینه تطبیق دهد. این نوشته تصمیم نهایی برای افزودن runtime یا اجازه‌ی دانلود نیست.

### مرجع roadmap و متن دقیق مرتبط

منبع بررسی‌شده: `/home/user/uploads/MASTER-ROADMAP-v4-FINAL.md` (نسخه‌ی ۴.۰؛ فایل بیرون از clone و Git). شماره‌خط‌ها به همین فایل در workspace اشاره می‌کنند.

- **مدل واحد، نه dual-model** — خلاصه‌ی مدیریتی، خط ۱۸: `| مدل زبانی | یک مدل واحد (Qwen3-4B، Q4_K_M) — نه Dual-Model Routing |`. آغاز Phase VIII در خط ۱۳۷۲ آن را «یک مدل واحد (Qwen3-4B، Q4_K_M، حدود ۳ گیگ حافظه)» می‌خواند. این یک فرض/هدف معماری است، نه اندازه‌گیری دستگاهی.
- **GEN-NET-1، خطوط ۱۴۰۲–۱۴۲۹**: فقط دریافت اولیه‌ی asset مدل را مسیر شبکه‌ای تعریف می‌کند؛ شروطش عبارت‌اند از `فقط HTTPS (TLS 1.2+)`، `HTTP Range Requests` برای resume، تأیید `SHA-256` پیش از READY، retry با exponential backoff، و گرفتن تأیید صریح کاربر اگر شبکه metered باشد. Wi‑Fi-only به‌عنوان پیش‌فرض صریح در این متن نیامده؛ شرط Wi‑Fi-only پیش‌فرض در این طراحی از دستور فعلی کاربر می‌آید و از roadmap نقل نمی‌شود.
- **GEN-EDGE-1، خطوط ۱۴۳۸–۱۴۶۳**: انتخاب CPU در برابر GPU باید از داده‌ی واقعی `GEN-PERF-1` بیاید؛ benchmark با WorkManager، شارژ و حداقل ۵ دقیقه idle و `n≥۲۰` تعریف شده؛ معیارها Prefill p95، Decode پایدار و Peak Anonymous Memory هستند؛ ایمنی بر سرعت اولویت دارد؛ خرابی GPU باید از مسیر `GEN-310` به CPU fallback کند. خط APK Size می‌گوید فقط `arm64-v8a` بسته‌بندی شود. این‌ها در Phase H به‌عنوان الزام طراحی ثبت می‌شوند، نه به‌عنوان اجرا/اندازه‌گیری انجام‌شده.
- **GEN-310، خطوط ۱۵۳۶–۱۵۳۷** — متن عیناً: `#### GEN-310 — Model Failure Recovery (بازتعریف‌شده، شامل GEN-292 Model Retry قدیم)` و `شامل هم Retry ساده (خطای گذرا) و هم مسیر اتصال به GEN-NEW-3 (خطای منابع).` جز این، roadmap تعداد/فاصله‌ی retry، طبقه‌بندی کامل exceptionها یا سیاست CPU fallback را در تعریف خود GEN-310 عددگذاری نمی‌کند؛ CPU fallback صریحاً در بند GEN-EDGE-1 آمده است. هیچ مقدار تازه‌ای در این سند به GEN-310 نسبت داده نمی‌شود.
- **GEN-PERF-1، خطوط ۶۲۶–۶۷۰**: تعریف بازنگری‌شده‌ی بعدی، Prefill p50/p95 در contextهای جدا، Decode پایدار ۱۰ دقیقه‌ای، TTFT، Peak Anonymous Memory، cold-load، thermal و KV-cache مبتنی بر metadata واقعی را می‌خواهد؛ برای p95 حداقل ۲۰ و p99 حداقل ۵۰ نمونه، ۲–۳ warm-up و ثبت `{device, os_build, model_sha256, runtime_version, timestamp}`. بخش قدیمی‌تر خطوط ۶۰–۷۶ هنوز از Peak RSS می‌گوید؛ اصلاح بعدی صریحاً RSS را برای تصمیم memory رد و Anonymous Memory را ملاک می‌کند، بنابراین این طراحی از اصلاح دیرتر پیروی می‌کند.
- **Contract #1، خطوط ۸۰–حدود ۱۲۵، و GEN-NEW-E، خط ۱۲۹۸**: `InferenceProvider` باید schema، streaming اختیاری و capabilityهای backend را حمل کند؛ GBNF در llama.cpp (یا MediaPipe فقط اگر واقعاً پشتیبانی شود) گزینه‌ی roadmap است. پیاده‌سازی فعلی پروژه هنوز backendی را به آن وصل نکرده است.
- خلاصه‌ی roadmap Hilt را به‌عنوان DI استاندارد نام می‌برد، درحالی‌که repository فعلی `AgentContainer` سفارشی دارد. این Phase H هیچ migration معماری/DI پیشنهاد یا اجرا نمی‌کند؛ adapter آینده باید به مرزهای موجود وصل شود مگر تصمیم مستقلی صادر شود.

### مرزهای الزام‌آور

- در این فاز **هیچ** کد runtime، dependency، مجوز جدید، network client، chat UI یا تغییر در رفتار Panic Reset ساخته نمی‌شود.
- Manifest فعلی مجوز `INTERNET` ندارد؛ افزودن آن در Phase H ممنوع و خارج از scope است. هر مسیر in-app network در فاز آینده باید تا approval جداگانه‌ی کاربر متوقف بماند.
- `SecretManager` داخلی تغییر نمی‌کند. کلیدهای Provider از همان API فعلی عبور می‌کنند؛ توکن احتمالی مخزن مدل، کلید Provider محسوب نمی‌شود و نباید به vault ارائه‌دهنده تبدیل شود.
- هر inference یا tool/action آینده باید از مسیر قراردادها و gateهای `AgentContainer` عبور کند؛ هیچ مسیر جدیدی نباید App Lock، SafetyGuard یا کنترل اجرای Skill را دور بزند.
- Phase G فقط در JVM/Robolectric آزموده شده است. Device verification، Android Keystore واقعی، restart واقعی process، ABI/GPU، benchmark و رفتار lifecycle هنوز بازند.

### وضعیت پیاده‌سازی در clone

- `app/src/main/kotlin/inference/InferenceEngine.kt`: `LocalInferenceEngine` بدون delegate واقعی هنوز deterministic placeholder است؛ وزن مدل/runtime در production به آن متصل نیست. `capabilities()` حتی در این حالت `supports_streaming`, `supports_structured_output` و `supports_cancellation` را `true` می‌دهد، پس این flags به‌تنهایی مدرک قابلیت واقعی نیستند.
- `contracts.InferenceRequest` فقط `onToken` و `streamState` دارد و cancellation handle/token ندارد؛ بااین‌حال provider فعلی cancellation را supported اعلام می‌کند. تا cancel به JNI/runtime برسد و روی دستگاه آزموده شود، اعلام capability یا cancellation end-to-end اثبات‌نشده است.
- ورودی admission در `LocalInferenceEngine.generate()` تعداد `request.context.length` را تقریباً با token برابر می‌گیرد و prompt/template/tokenizer واقعی را نمی‌شمارد؛ بنابراین guard فعلی سقف ۴K را device-safe ثابت نمی‌کند.
- `app/src/main/kotlin/reasoning/CognitiveExecutionEngine.kt`: `maxParseRetries=1` یعنی حداکثر یک retry برای parse؛ کد همچنین هر `InferenceProvider` failure را یک بار دوباره امتحان می‌کند. این رفتار نه error-specific است و نه به‌تنهایی پیاده‌سازی کامل GEN-310 (resource recovery/GPU→CPU fallback) محسوب می‌شود.
- `app/src/main/kotlin/inference/ModelDownloadProtocol.kt`: utility دانلود/هش با `HttpStreamSource` تزریقی است؛ network source production و wiring به startup در کد فعلی پیدا نشد. پاسخ Range/Content-Range، staging durable، محدودیت اندازه و cancellation واقعی در آن validate نمی‌شوند؛ داده در `ByteArrayOutputStream` جمع می‌شود.
- `app/src/main/kotlin/reasoning/StructuredGrammarConstraint.kt`: GBNF و JSON Schema را تولید و خروجی را پس از generation parse/validate می‌کند، اما decoding constrained به backend بومی وصل نیست و grammar/schema فعلی mismatchهای §۲ را دارد.
- `app/src/main/kotlin/inference/ContextWindowBudgetPolicy.kt`: سقف جاری ۴۰۹۶ token، safety margin ۶۴ token و KV coefficient برابر ۱۴۴ KiB/token هستند؛ roadmap و source هر دو آن‌ها را provisional تا GEN-PERF-1 می‌دانند.
- `app/build.gradle.kts`: `minSdk=28`, `compileSdk=34`, `targetSdk=34`; runtime جدید، NDK/CMake build یا `abiFilters` فعلی وجود ندارد. Manifest مجوز `INTERNET` ندارد.

## ۲. قراردادهای جاری و نقاط اتصال

| قرارداد/مسیر | رفتار موجود | پیامد برای طراحی |
|---|---|---|
| `contracts.InferenceProvider` | `capabilities()` و `generate(request): Result<InferenceResponse>`؛ capabilityها streaming/structured output/cancellation را توصیف می‌کنند. `LocalInferenceEngine` فعلاً هر سه را حتی با placeholder=true اعلام می‌کند. | adapter باید capability را از backend واقعاً bound‌شده گزارش کند؛ placeholder flag مدرک runtime نیست. `supports_cancellation=true` فقط پس از اثبات توقف native و آزادسازی منابع مجاز است. |
| `InferenceRequest` | `prompt`, `context`, `GenerateParams`, `outputSchema?`, callback اختیاری `onToken`, و `StreamState` دارد. | callback برای متن tokenها هست، ولی request cancellation handle/token ندارد. Cancellation contract باید پیش از ادعای لغو حل شود؛ تغییر Contract Freeze بدون تصمیم و versioning انجام نشود. |
| `StructuredGrammarConstraint` | Contract #3 را به JSON Schema و یک رشته‌ی GBNF تبدیل می‌کند؛ سپس `StructuredCognitiveCall` را deserialize و `confidence` را در `[0,1]` کنترل می‌کند. | محدودیت پس از generation جای constrained decoding واقعی را نمی‌گیرد. schema/grammar و post-validation باید یک منبع حقیقت داشته باشند. |
| `ContextWindowBudgetPolicy` | usable context = سقف مدل − output درخواستی − safety margin؛ تخمین فعلی ۱۴۴ KiB برای هر token است. | شمارش ورودی باید با tokenizer/chat template runtime یکی باشد؛ شمارش کاراکتر به‌تنهایی قابل قبول نیست. تخمین RAM معیار اندازه‌گیری دستگاهی نیست. |
| `ModelDownloadProtocol` | HTTPS prefix، سؤال metered، retry محدود و SHA-256 دارد؛ `HttpStreamSource` را از caller می‌گیرد. داده در `ByteArrayOutputStream` جمع می‌شود. | برای فایل حدود ۲٫۵ GB طراحی production نیست: partial durable، کنترل واقعی Range/Content-Range، محدودیت طول، storage staging و download cancellation در آن وجود ندارد؛ نگه‌داشتن کل asset در heap نامناسب است. |

### ناسازگاری‌های مشخص GBNF/JSON Schema فعلی

1. GBNF تمام هفت فیلد را به ترتیب ثابت و از جمله `tool_name` و `tool_params` اجباری می‌کند؛ JSON Schema فعلی آن دو را الزامی نمی‌کند.
2. قاعده‌ی `string` در GBNF escapeهای معتبر JSON مانند `\\"`, `\\\\` و newline را کامل پوشش نمی‌دهد.
3. قاعده‌ی GBNF برای `confidence` عدد ساده‌ی غیرمنفی است و بازه‌ی `[0,1]` را enforce نمی‌کند؛ این بازه فعلاً فقط در post-validation کنترل می‌شود.
4. schema باید پس از decode همچنان مرجع اعتبارسنجی باشد؛ خروجی runtime یا prompt به‌تنهایی مجوز اجرای tool نیست.

پیش از انتخاب backend باید یا grammar از schema canonical تولید و round-trip آزموده شود، یا backendی انتخاب شود که JSON Schema را مستقیماً enforce کند. در هر دو حالت parse/validation مستقل و رد خروجی malformed حفظ شود.

## ۳. مدل منتخب برای ارزیابی: Qwen3-4B

### ۳.۱ فایل GGUF Q4_K_M

Candidate اصلی که با یادداشت فعلی پروژه (Qwen3-4B و 4K context) هم‌خوانی اسمی دارد:

| فیلد | مقدار مشاهده‌شده |
|---|---|
| Repository / file | `Qwen/Qwen3-4B-GGUF` / `Qwen3-4B-Q4_K_M.gguf` |
| Repository revision در فهرست بررسی‌شده | `bc640142c66e1fdd12af0bd68f40445458f3869b` |
| اندازه‌ی دقیق فایل | `2,497,280,256` bytes (حدود 2.50 GB ده‌دهی؛ حدود 2.33 GiB) |
| SHA-256 / LFS OID | `7485fe6f11af29433bc51cab58009521f205840f5b4ae3a32fa7f92e8534fdf5` |
| مجوز model card | Apache-2.0 |
| Context evidence | README مدل 32,768 native؛ `config.json` مقدار `max_position_embeddings=40,960` را گزارش می‌کند؛ metadata/revision GGUF بررسی‌شده نیز 40,960 نشان می‌دهد. |
| سقف برنامه | `ContextWindowBudgetPolicy` هنوز 4,096 است؛ این سند آن را بالا نمی‌برد. |

مقادیر 32,768 و 40,960 با هم ناسازگارند؛ نباید حداکثر مدل را از روی README یا config به‌تنهایی فرض کرد. برای v1 برنامه، سقف 4K قرارداد عملیِ کد می‌ماند و context بزرگ‌تر نیازمند آزمون tokenizer، KV memory و runtime روی دستگاه است. URLهای mutable مانند `main` برای انتشار کافی نیستند؛ هر asset باید به revision و SHA بالا پین شود.

### شکاف generation parameters برای Qwen

README رسمی Qwen برای thinking mode مقادیر پیشنهادی `temperature=0.6`, `top_p=0.95`, `top_k=20`, `min_p=0`, `presence_penalty=1.5` را می‌دهد؛ برای non-thinking، `temperature=0.7`, `top_p=0.8`, `top_k=20`, `min_p=0`, `presence_penalty=1.5`. قرارداد فعلی `GenerateParams` فقط `max_tokens`, `temperature`, `top_p`, `mode` را دارد و `top_k`, `min_p`, `presence_penalty`, `enable_thinking` یا thinking budget را صریح نمی‌کند. این gap باید پیش از PoC با runtime-specific config یا contract versioning حل شود؛ مقدارهای توصیه‌شده‌ی README نیازمندی سخت پروژه یا اندازه‌گیری performance نیستند.

### ۳.۲ LiteRT-LM community artifacts

Model card نسخه‌ی `8b4c3d3f6e727cdb445f42feff14b808db8da4fa` دو artifact Qwen3-4B را فهرست می‌کند:

| Artifact | Quantization / KV | Context مدل‌کارت | اندازه‌ی مدل‌کارت | ارزیابی اولیه |
|---|---|---:|---:|---|
| `qwen3_4b_mixed_int4.litertlm` | TorchAO mixed INT4: projectionهای واجدشرایط blockwise INT4 با group 32؛ embedding وزن INT8؛ normalization/reduction و KV floating point | 2,048 | 2,535.88 MiB | از نظر artifact size نزدیک‌مرتبه به GGUF است، اما context آن کمتر از سقف برنامه‌ی 4,096 است؛ drop-in نیست. |
| `qwen3_4b_channelwise_int8_float32kv.litertlm` | channel-wise INT8 و float32 KV | 4,096 | 5.28 GB | context فعلی را پوشش می‌دهد، اما footprint دیسک بسیار بزرگ‌تری دارد و RAM/latency واقعی روی target هنوز اثبات نشده است. |

هر دو طبق model card به LiteRT-LM `0.18.0+` نیاز دارند؛ artifactها از Qwen3 template/config استفاده می‌کنند. مدل‌کارت می‌گوید thinking به‌صورت پیش‌فرض روشن است و thought channel/tool parsing را runtime مدیریت می‌کند؛ thought می‌تواند context کوچک را مصرف کند. باید در PoC بین `enable_thinking=false` و `thinking_token_budget` تصمیم آزموده شود؛ هیچ‌یک در این فاز تنظیم نشده است.

جدول performance همان model card، Android اعداد را برای LiteRT-LM `v0.13.1`, GPU OpenCL، prefill=256 و decode=64 (iteration گرم از اجرای دو iteration) گزارش می‌کند. این‌ها اندازه‌گیری مستقل/تکرارشده‌ی پروژه نیستند، برای `v0.18.0` نیستند و به دلیل تفاوت دستگاه، artifact، backend و تنظیمات با GGUF/llama.cpp قابل مقایسه‌ی مستقیم نیستند؛ برای انتخاب release به آن‌ها تکیه نشود.

## ۴. مقایسه‌ی runtimeها و binding آماده

| گزینه | Qwen3-4B / GGUF / GBNF | Streaming / cancellation | Build، ABI و اندازه | ارزیابی برای این اپ |
|---|---|---|---|---|
| **MediaPipe LLM Inference** (`tasks-genai:0.10.27`) | راهنمای Android artifact و API قدیمی را نشان می‌دهد؛ بارگذاری مستقیم فایل دقیق GGUF Q4_K_M و تطبیق Qwen در این مسیر اثبات نشده. مسیر GBNF در منبع بررسی‌شده مشخص نیست. | API قدیمی streaming/cancel دارد؛ توقف واقعی و حفظ state در adapter این اپ باید آزموده شود. | مصرف به‌صورت Android dependency؛ تغییر APK نهایی/اندازه‌ی artifact در این پروژه اندازه‌گیری نشده. | راهنمای رسمی API را maintenance-only می‌خواند؛ برای شروع جدید اولویت پایین. |
| **LiteRT-LM** (`0.18.0+`) | API به artifact `.litertlm` نیاز دارد، نه GGUF مستقیم. model card موجود: mixed-INT4 با context=2,048 و 2,535.88 MiB؛ channelwise-INT8/FP32-KV با context=4,096 و 5.28 GB. `ResponseFormat` JSON Schema/Regex دارد؛ parity با GBNF پروژه اثبات نشده. | `Conversation.cancelProcess()` موجود است؛ semantics لغو و rollback state از adapter باید جداگانه اثبات شود. | Kotlin Android API و native packages؛ APK delta این پروژه اندازه‌گیری نشده؛ model جدا از APK است. | مسیر رسمی Google، اما برای نگه‌داشتن همان GGUF Q4_K_M drop-in نیست؛ گزینه‌ی PoC فقط با پذیرش تبدیل artifact یا تغییر مدل. |
| **llama.cpp از upstream، JNI/CMake** | GGUF را مستقیم می‌خواند و GBNF را پشتیبانی می‌کند؛ grammar فعلی پروژه پیش از اتصال باید با JSON Schema هم‌ارز شود. | مسیر callback/stop باید تا API native، آزادسازی session و cancellation Contract آزموده شود. | NDK + CMake + JNI و ساخت native برای ABI لازم؛ CPU fallback باید baseline باشد. OpenCL اندروید عمدتاً Adreno-محور است. APK نهایی هنوز ساخته/اندازه‌گیری نشده. | بهترین تطبیق مفهومی با GGUF+GBNF و امکان build از source؛ بیشترین هزینه‌ی native maintenance و build. |
| **Binding آماده‌ی `net.ladenthin:llama-android:5.1.0`** | AAR منتشرشده روی Maven Central؛ README نسخه‌ی tag `v5.1.0` بارگذاری GGUF از path را نشان می‌دهد و API هسته `InferenceParameters.withGrammar()` دارد؛ JSON-Schema→grammar هم در README هسته معرفی شده است. اتصال schema/grammar واقعی این پروژه و Qwen3 end-to-end هنوز اثبات نشده. مدل داخل AAR نیست. | façade اختیاری `llama-kotlin:5.1.0`، Flow و suspend دارد و cancellation را تا token boundary می‌فرستد؛ اما README آن Kotlin 2.3+ می‌خواهد، درحالی‌که پروژه Kotlin 1.9.22 دارد. افزودن آن بدون تصمیم/تغییر نسخه‌ی جداگانه مجاز نیست؛ Java API به‌تنهایی هنوز به wrapper/cancel tests نیاز دارد. | Android AAR، minSdk 28 و ABIهای arm64-v8a و x86_64 در zip واقعی دیده شدند؛ consumer NDK build لازم ندارد. اندازه‌ی AAR دانلود و بررسی‌شده `19,839,083` bytes؛ SHA-256 `8bae2f2e9eaeb3c8a971813f8e819326c1a7ded9308ca66f1db6dd3e0ca5ab72`. داخل آن `libjllama.so`: arm64 `25,219,576` و x86_64 `25,428,704` bytes uncompressed. **APK/AAB نهایی این اپ اندازه‌گیری نشده**؛ اندازه‌ی AAR را نباید با APK delta یکی گرفت. | نامزد مناسب برای PoC کم‌هزینه‌تر از NDK خودمان، به شرط بازبینی source/provenance/license و runtime تست. در تاریخ بررسی Maven Central نسخه‌ی 5.1.0 را برگرداند؛ source `main` که 5.2.0 را تبلیغ می‌کند از نظر publication با Maven metadata تأیید نشد (POM نسخه‌ی 5.2.0 در این بررسی 404 بود)، پس 5.2.0 را انتخاب‌شده فرض نکن. |

**APK-size status:** در `app/build/outputs` فعلاً APK/AAB وجود ندارد؛ هیچ عددی برای اندازه‌ی نهایی برنامه در دست نیست. `app/build.gradle.kts` هم `abiFilters` تعریف نمی‌کند. برای هر runtime باید بعد از PoC، debug/release APK و AAB و اندازه‌ی split نصب‌شده‌ی arm64 جداگانه اندازه‌گیری شود؛ مطابق roadmap، بسته‌بندی arm64-only یک گزینه‌ی اندازه است، نه تغییری که این Phase انجام داده باشد. وزن مدل Qwen (`2,497,280,256` bytes) در این مقایسه خارج از APK و به‌صورت asset جدا فرض شده است.

### موضع پیشنهادی — هنوز انتخاب نهایی نیست

- MediaPipe برای پروژه‌ی جدید انتخاب پیش‌فرض نباشد، چون راهنمای رسمی maintenance-only است.
- برای حفظ فایل GGUF، GBNF و context 4K دو گزینه‌ی قابل مقایسه در PoC آینده‌اند: build از source با upstream llama.cpp، یا AAR منتشرشده‌ی `net.ladenthin:llama-android:5.1.0`. اولی کنترل source بیشتری ولی بار NDK دارد؛ دومی build ساده‌تر ولی native binary و supply chain بیرونی دارد. این سند هیچ‌کدام را انتخاب نمی‌کند.
- اگر AAR PoC شود، ابتدا Java API آن با نسخه‌ی موجود Kotlin 1.9.22، قرارداد schema فعلی و cancellation بررسی شود. façade اختیاری `llama-kotlin:5.1.0` طبق README Kotlin 2.3+ می‌خواهد؛ افزودن/ارتقای Kotlin ممنوع از دامنه‌ی این نوبت و نیازمند approval جداست.
- LiteRT-LM فقط در صورت پذیرش تبدیل artifact/تغییر model و حل اختلاف context؛ mixed INT4 فعلی 2K برای قرارداد 4K کافی نیست و INT8/FP32-KV با 5.28 GB باید از نظر storage/RAM روی target ثابت شود.
- انتخاب نهایی منوط به test matrix و approval مستقل کاربر برای dependency/native code، مدل، permission و هر تغییر Contract Freeze است. نسخه‌ی reproducible باید دقیق pin شود، نه `latest.release`.

## ۵. طرح acquisition، hash، resume، consent و storage

### واقعیت فعلی و محدودیت شبکه

`ModelDownloadProtocol` concrete source ندارد و برای resume فقط `openRangeStream(url, startByte)` را به یک abstraction واگذار می‌کند؛ خودش HTTP `206`, `Content-Range`, ETag, redirect policy یا persistence بین processها را validate نمی‌کند. کل byte stream در RAM با `ByteArrayOutputStream` جمع و در پایان hash می‌شود. برای asset حجیم، process death و disk exhaustion این کافی نیست. Manifest نیز `INTERNET` ندارد؛ هیچ in-app download در وضعیت فعلی مجاز یا قابل ادعا نیست.

### دو مسیر آینده، نیازمند تصمیم جداگانه

1. **Offline/import-first (بدون `INTERNET`):** کاربر asset را خارج از برنامه می‌گیرد و در آینده از system document picker (`ACTION_OPEN_DOCUMENT`) انتخاب می‌کند؛ app آن را به staging خصوصی stream می‌کند، اندازه و SHA را بررسی می‌کند و تا پایان verify آن را load نمی‌کند. این گزینه permission شبکه لازم ندارد، ولی هنوز نیازمند طراحی UI/consent/فضای ذخیره و کد آینده است.
2. **In-app download:** تنها پس از approval جداگانه‌ی کاربر درباره‌ی اضافه‌کردن `INTERNET` و endpointهای قابل‌اعتماد. transfer باید کار صریح و قابل لغو باشد؛ silent startup download ممنوع. permission یا client در Phase H اضافه نشده است.

### الزامات طراحی برای هر مسیر

- manifest asset شامل repository/file، commit ثابت، byte length و SHA-256 64-رقمی باشد؛ برای GGUF candidate بالا hash و size جدول §3.1 مرجع‌اند. `main`, filename-only یا ETag به‌تنهایی pin کافی نیست.
- نوشتن chunkها مستقیم در فایل `.part` زیر `noBackupFilesDir/models/` یا storage داخلی خصوصی انجام شود؛ هیچ external/public path، log مدل، key یا signed URL وجود نداشته باشد. شروع inference فقط پس از length+SHA کامل و rename اتمیک به فایل `READY` مجاز است.
- برای resume، طول partial محلی به‌تنهایی کافی نیست: پاسخ Range، status، offset، `Content-Range` و total length با هم validate شوند؛ اگر سرور Range را نپذیرفت، از صفر شروع یا خطای امن بدهد. progress metadata crash-safe باشد. partial هرگز مثل مدل معتبر load نشود.
- طول مورد انتظار از manifest ثابت باشد، ورودی network-controlled سقف نداشته باشد. فضای آزاد قبل و حین انتقال بررسی شود؛ failure/cancel نباید مدل قبلی یا data کاربر را حذف کند. انتقال hash را incremental حساب کند، نه با بارگذاری کل artifact در heap.
- پیش از هر انتقال، consent روشن و قابل لغو برای نام/نسخه، اندازه، SHA، مجوز، مصرف دیتا، محل خصوصی ذخیره و امکان resume بگیرد؛ هیچ silent startup download نباشد.
- **پیش‌فرض صریح: Wi‑Fi-only.** انتقال شبکه‌ای فقط روی Wi‑Fi فعال و unmetered شروع/ادامه یابد. Cellular یا هر شبکه‌ی metered مسدود بماند، مگر کاربر برای همان دانلود یک‌بار جداگانه opt-in کند؛ این consent استثنای موردی است و پیش‌فرض را تغییر نمی‌دهد. صرف `isActiveNetworkMetered=false` به‌تنهایی مجوز cellular نیست، چون قید Wi‑Fi transport هم لازم است.
- hash، provenance و license همزمان با artifact ثبت شوند؛ SHA فقط تمامیت بایت‌های موردانتظار را می‌سنجد و به‌تنهایی امضای ناشر/ایمنی مدل را ثابت نمی‌کند. در صورت mismatch فایل staged قابل استفاده نیست؛ داده‌های دیگر هرگز پاک نشوند.
- سیاست نگه‌داری/حذف مدل explicit باشد. Phase H رفتار Panic Reset را تغییر نمی‌دهد؛ نه App Lock/PIN failure و نه Panic Reset نباید در این طرح به‌طور ضمنی وزن مدل، PIN یا داده‌ی کاربر را پاک کنند. حذف/جایگزینی model asset فقط با UI و تأیید صریح جداگانه؛ Provider API keys در metadata/manifest مدل قرار نگیرند.

## ۶. بودجه‌ی حافظه، اجرا و حفاظ ایمنی

- سقف برنامه 4,096 context، خروجی و حاشیه‌ی 64 token را از ظرفیت usable کم می‌کند. tokenizer و Qwen chat template باید همین حساب را تغذیه کنند؛ پیام‌های thought/internal نباید بدون تصمیم ذخیره یا به Memory برگردند. این اعداد هنوز provisional هستند.
- **هزینه‌ی وزن و KV جداست:** فایل Q4_K_M برابر `2,497,280,256` bytes (حدود `2.326 GiB`) روی storage است. `Qwen3-4B config.json` مقادیر `num_hidden_layers=36`, `num_key_value_heads=8`, `head_dim=128` را گزارش می‌کند. با فرض KV از نوع FP16، فرمول metadata roadmap می‌دهد: `36 × 8 × 2 (K,V) × 128 × 2 bytes = 147,456 bytes/token = 144 KiB/token`; برای 4,096 token، `603,979,776 bytes = 576 MiB`. این با محاسبه‌ی `estimateKvCacheBytes()` برابر است؛ اما KDoc همان policy می‌گوید `~590 MB`, پس گردکردن/واحد در KDoc دقیق نیست. مقدار KV dtype/runtime-dependent است و اندازه‌گیری نشده.
- جمع صرفاً حسابیِ فایل وزن + KV فرضی FP16 در 4K حدود `3,101,260,032 bytes` یا `2.888 GiB` است؛ این **peak RAM نیست**. وزن‌ها ممکن است mmap/file-backed و قابل reclaim باشند؛ native scratch/workspace، allocator, tokenizer, Android app/OS و residency واقعی جدا هستند. عدد roadmap «حدود ۳ گیگ» با این جمع مرتبه‌ای سازگار است، اما benchmark جایگزین نمی‌شود.
- GEN-PERF-1 در roadmap بازنگری‌شده Peak **Anonymous Memory** را معیار fallback می‌داند، نه RSS ترکیبیِ شامل صفحات mmap. در PoC cold/warm load، anonymous memory، KV، TTFT، Prefill p50/p95 برای طول‌های مجزا، Decode پایدار ۱۰ دقیقه‌ای، thermal/battery/failureها روی ARM64 ثبت شوند؛ طبق roadmap n≥20 برای p95، n≥50 برای p99، دو تا سه warm-up و fingerprint شامل device/OS/model SHA/runtime version/timestamp. RSS/PSS می‌توانند تشخیصی ثبت شوند، اما جای metric تصمیم‌گیری را نگیرند. هیچ اندازه‌گیری دستگاهی در Phase H انجام نشده.
- طبق متن صریح GEN-EDGE-1، خطای GPU Delegate باید به CPU fallback برسد؛ OOM/thermal به `ResourceExhausted` و مسیر `GEN-NEW-3` وصل شود. اگر CPU هم fail شد یا backend/capability نامعلوم بود، درخواست fail closed شود و هیچ خروجی ناقص/مدل‌ساخته‌ای مجوز اجرای Skill نگیرد. threshold و تعداد retry برای GEN-310 در roadmap مشخص نشده‌اند؛ عدد تازه اضافه نشود.
- GPU فقط با profile سخت‌افزاری تازه و safety/canary gate موجود اجازه گیرد؛ Runtime جدید نباید `GPU_DISABLED`, `LOW_MEMORY`, WorkManager benchmark یا fail-closed logic را دور بزند.
- پردازش مدل روی main thread ممنوع؛ engine/conversation lifecycle و یک‌نوبتی/serialization هر session روشن باشد. cancellation باید واقعاً به backend برسد؛ اگر Contract Freeze تغییر نمی‌کند، API افزایشی و سازگار طراحی شود و `supports_cancellation=true` تا اثبات cancel واقعی برنگردد.

### ۶.۱ محل build، اندازه‌ی APK و مقصد آزمون

| محل | نقش مناسب | شواهد/هزینه | تصمیم طراحی |
|---|---|---|---|
| **Workspace فعلی** | Gradle/JVM، lint و در آینده build عادی APK بدون source-native | JDK 21 و Android SDK در دسترس‌اند؛ NDK/CMake در SDK نصب‌شده و `cmake` executable پیدا نشد. حافظه `MemTotal=2,030,412 kB` (حدود 1.94 GiB)، `MemAvailable≈1.30 GiB` و swap صفر است. APK/AAB در `app/build/outputs` موجود نیست. | برای آزمون‌های فعلی JVM و کار غیر-native مناسب است؛ native CMake build را اینجا هدف نگیریم. |
| **Termux روی گوشی** | در صورت دسترسی، نصب/اجرای دستی و سنجش روی همان گوشی | Termux/گوشی هدف در این workspace مشاهده نشده؛ RAM، storage، SDK/NDK، signing و قابلیت build آن دستگاه **اثبات‌نشده** است. build طولانی native روی گوشی می‌تواند با محدودیت حرارت/حافظه/فضا برخورد کند. | محیط canonical برای APK release نیست؛ فقط محیط اختیاریِ آزمایش/benchmark دستگاه پس از نصب. |
| **GitHub Actions یا CI مشابه** | build تکرارپذیر APK/AAB؛ همچنین source build native در صورت انتخاب llama.cpp upstream | مستند رسمی GitHub برای runner استاندارد Linux می‌گوید public repo: 4 CPU/16 GB RAM/14 GB SSD؛ private repo: 2 CPU/8 GB RAM/14 GB SSD. NDK/CMake نسخه‌دار باید در workflow نصب/pin شود؛ ظرفیت واقعی build این پروژه هنوز امتحان نشده. | گزینه‌ی ترجیحی برای build/release بعدی؛ native build ابتدا با parallelism پایین آزموده شود، و اگر OOM شد runner بزرگ‌تر/self-hosted. مدل 2.5GB را در build job دانلود نکنیم؛ model asset خارج از APK است. این Phase workflow اضافه نمی‌کند. |
| **گوشی Android هدف واقعی** | نصب APK/AAB، cold/warm load، memory/thermal/CPU/GPU و cancellation | تنها محیطی که می‌تواند GEN-PERF-1 و رفتار واقعی ABI/GPU را اثبات کند؛ شناسنامه‌ی دستگاه هدف هنوز در دسترس نیست. | مقصد device verification و benchmark است، نه منبع build قابل‌بازتولید. |

**اندازه‌ی قابل استناد فعلی:** فقط AAR آماده‌ی `llama-android:5.1.0` در §۴ اندازه‌گیری شده است؛ فایل AAR برابر `19,839,083` bytes است و کتابخانه‌ی arm64 داخل zip `25,219,576` bytes uncompressed. این نه اندازه‌ی نهایی APK است و نه شامل وزن Qwen. APK/AAB نهایی، ABI split، امضای release و deltaی وابستگی در app در این فاز ساخته یا اندازه‌گیری نشده‌اند. برای انتخاب نهایی باید اندازه‌ی baseline، universal/debug و arm64 split/release و اندازه‌ی native استخراج‌شده جدا گزارش شوند؛ مدل 2,497,280,256-byte asset در محاسبه‌ی APK جدا بماند.

**مسیر پیشنهادی build/test:** source/native compilation (اگر انتخاب شد) و Gradle package روی CI نسخه‌دار؛ artifact دقیق به گوشی واقعی نصب و benchmark شود. Termux فقط اگر در دسترس بود برای آزمایش تکمیلی است. release signing باید در مسیر محافظت‌شده و خارج از repository انجام شود؛ هیچ workflow، signing key یا credential در Phase H ساخته/ثبت نشده است.

## ۷. نقشه‌ی وابستگی Phase G → H → ط → ی

این mapping بین ترتیب تحویل مورد درخواست کاربر و وابستگی رسمی roadmap تمایز می‌گذارد. Roadmap در «ترتیب ساخت» GEN-PERF-1 را قدم صفرِ پیش از تصمیم‌های عددی، Phase VIII (GEN-271/278/285) را پایه‌ی زودهنگام، و لایه‌ی امنیت/Phase XIII را قدم ۱۲ می‌گذارد. برای این کار، ترتیب تحویل صریح کاربر **ز → ح → ط → ی** است؛ این ترتیب به‌معنی حذف/تغییر وابستگی‌های roadmap نیست.

1. **Phase G / ز — Security UI و gateها:** پیاده‌سازی و JVM-tested؛ device verification باز. Credential entry، App Lock، Reset/release UX باید مرز تمام runtimeهای بعدی بمانند.
2. **Phase H / ح — این سند:** ثبت متن roadmap موجود، current contract، model/memory evidence، runtime/binding trade-offs، download/permission conflict، محل build، اندازه‌ی artifact و acceptance criteria؛ design-only، بدون runtime/dependency/permission.
3. **Phase ط — انتخاب و PoC اجرا:** فقط پس از approval کاربر برای runtime/dependency/network یا import/storage و target device. به‌دلیل GEN-PERF-1، benchmark واقعی روی دستگاه هدف باید پیش از قفل هر threshold/GPU choice انجام شود؛ PoC نیز باید CPU baseline، GBNF/schema parity، cancellation، memory measurement و App Lock/Skill gates را پوشش دهد.
4. **Phase ی — سخت‌سازی و device verification:** pinning/reproducible release، failure/restart/integrity/resume/cancel tests، hardware/thermal/memory benchmark، ABI/GPU matrix و operational/privacy review؛ فقط با گزارش device evidence و approval.

## ۸. Exit criteria برای تصمیم/runtime PoC بعدی

- runtime/artifact versions، license و provenance pinned باشند؛ model size و SHA از همان revision مستقل بازبینی شوند.
- model context حداقل با app 4K budget سازگار باشد یا تصمیم صریح برای تغییر قرارداد داده شود؛ schema/GBNF mismatch رفع و adversarial/invalid-output tests سبز باشند.
- model را با CPU روی دستگاه واقعی ARM64 بارگذاری/اجرا و peak RAM, latency, thermal و restart/cancel اندازه‌گیری کنند؛ اگر runtime فقط Emulator/JVM را پشتیبانی کرد، acceptance ناقص بماند.
- process interruption، partial range, bad hash, wrong length, ENOSPC, permission denial, cancellation و retry بررسی شوند؛ هیچ failureای مدل نصفه را READY نکند و هیچ داده‌ی کاربر را ناخواسته پاک نکند.
- Package/Manifest فقط پس از approval تغییر کند. اگر شبکه لازم است، decision درباره‌ی `INTERNET`، host allowlist، metered consent و privacy جدا ثبت شود؛ در غیر این صورت offline import flow تثبیت شود.
- streaming/cancellation به قرارداد و SkillExecutionEngine سازگار باشد؛ output JSON/GBNF مستقل validate شود و model response به‌تنهایی هیچ tool را اجرا نکند.
- صفر credential/model text در logs/audit، lock-screen preview یا diagnostics؛ App Lock, SafetyGuard و Panic Reset behavior حفظ شوند.
- `supports_streaming`, `supports_structured_output`, `supports_cancellation` و `supports_seed` از backend واقعاً متصل و آزموده‌شده گزارش شوند؛ placeholder نباید capability production اعلام کند. Cancellation باید به native token/session برسد و بعد cancel منابع آزاد و state قابل‌پیش‌بینی باشد.
- GEN-310 بر اساس متن exact roadmap پیاده شود: retry گذرا و resource path به GEN-NEW-3، و GPU failure به CPU طبق GEN-EDGE-1. چون roadmap تعداد/backoff دقیق نمی‌دهد، هر عدد یا تغییر retry policy نیازمند تصمیم/نسخه‌بندی جداست؛ parse retry فعلی به‌تنهایی کفایت ندارد.
- اگر network path بعداً تصویب شد، دانلود به‌صورت پیش‌فرض Wi‑Fi transport + unmetered باشد؛ metered/cellular فقط با consent یک‌باره‌ی همان مدل، و progress/cancel/resume/hash روی process death آزموده شود.
- برای ABIهای انتخاب‌شده، APK/AAB baseline و post-runtime و arm64 installed split با ابزار یکسان اندازه‌گیری و delta گزارش شود؛ عدد AAR جای اندازه‌ی APK را نگیرد. مسیر `arm64-v8a` تنها طبق roadmap و بعد از تأیید ABI matrix انتخاب شود.

## ۹. ادعاهای اثبات‌نشده (تا دریافت evidence واقعی)

- هیچ runtimeی در app اجرا نشده؛ قضاوت درباره‌ی speed، peak anonymous memory، thermal behavior یا GPU/NPU portability روی دستگاه هدف **اثبات‌نشده** است.
- سازگاری end-to-end Qwen3-4B Q4_K_M با GBNF، tokenizer، chat template، output schema و context مجاز 4K **اثبات‌نشده** است؛ model/repository metadata میان 32,768 و 40,960 اختلاف دارد.
- وجود و اندازه‌ی AAR `llama-android:5.1.0` از Maven Central بررسی شده، اما مدل Qwen روی این binding در همین app بارگذاری/اجرا نشده؛ trust/provenance مستقل binary، parity grammar/schema، cancellation adapter، peak memory و عملکرد دستگاه **اثبات‌نشده** است. AAR-size اندازه‌گیری‌شده، APK delta نیست.
- README source روی `main` از 5.2.0 نام می‌برد، ولی publication آن در Maven Central در این بررسی **اثبات‌نشده** بود؛ POM درخواست‌شده 404 داد. فقط 5.1.0 را artifact منتشرشده‌ی مشاهده‌شده حساب می‌کنیم.
- LiteRT-LM community `.litertlm` artifacts به‌عنوان Android artifact قابل بارگذاری روی app/device versionهای هدف **اثبات‌نشده** هستند؛ مدل‌کارت، PoC دستگاه این پروژه نیست.
- MediaPipe/LiteRT-LM/llama.cpp cancellation و state rollback در adapter قراردادی این اپ **اثبات‌نشده** است. `InferenceRequest` cancellation token ندارد و capabilityهای placeholder اکنون بیش‌ازحد ادعا می‌کنند.
- GEN-310 در roadmap فقط retry گذرا و اتصال resource errors به GEN-NEW-3 را یک‌خطی مشخص می‌کند؛ تعداد/backoff و taxonomy تکمیلی retry **تعریف/اثبات‌نشده** است. CPU fallback برای خطای GPU در GEN-EDGE-1 آمده، ولی اجرای دستگاهی نشده.
- `ModelDownloadProtocol` امن/مقاوم برای فایل 2.5 GB، process restart یا HTTP resume **اثبات‌نشده** است؛ source production وجود ندارد، کد کل فایل را heap می‌کند و manifest عمداً `INTERNET` ندارد. Wi‑Fi-only policy نیز فعلاً فقط معیار طراحی است، نه رفتار اجراشده.
- هیچ APK/AAB نهایی وجود ندارد؛ final package size، ABI split، native size پس از AGP، signing و build روی CI **اثبات‌نشده** هستند. محیط Termux و مشخصات گوشی هدف هم در دسترس/تأیید نیستند.
- هیچ production provider descriptor/transport registration در `src/main` پیدا نشد؛ بنابراین credential ID test، اتصال live provider یا network inference را ثابت نمی‌کند.
- مقادیر KV/RSS، model readiness، performance گزارش‌شده در model card و معادل‌بودن emulator/Robolectric با hardware **اثبات‌نشده** هستند تا device measurements مستقل افزوده شوند.

## ۱۰. مراجع و شواهد بیرونی

تاریخ/نسخه‌ی دقیق هنگام پیاده‌سازی مجدداً بررسی شود؛ صفحات mutable با revisionهای زیر پین شوند.

### مدل Qwen

- [Qwen3-4B-GGUF README](https://huggingface.co/Qwen/Qwen3-4B-GGUF/raw/main/README.md) — Apache-2.0، GGUF، quantizations و README context=32,768.
- [Qwen3-4B config.json](https://huggingface.co/Qwen/Qwen3-4B/blob/main/config.json) — snapshot بررسی‌شده `max_position_embeddings=40960`, `num_hidden_layers=36`, `num_key_value_heads=8`, `head_dim=128` گزارش می‌کند؛ اختلاف context با README باید باقی بماند تا metadata/runtime pinned و روشن شود. URL فعلاً mutable است و در PoC باید commit ثابت ثبت شود.
- [Q4_K_M file at pinned revision](https://huggingface.co/Qwen/Qwen3-4B-GGUF/blob/bc640142c66e1fdd12af0bd68f40445458f3869b/Qwen3-4B-Q4_K_M.gguf) — size و LFS SHA-256 در §3.1.
- [Pinned repository metadata](https://huggingface.co/api/models/Qwen/Qwen3-4B-GGUF) — revision/tree metadata برای بازبینی artifact.
- [LiteRT-community Qwen3-4B README at pinned revision](https://huggingface.co/litert-community/Qwen3-4B/blob/8b4c3d3f6e727cdb445f42feff14b808db8da4fa/README.md) — quantization, context, size, runtime minimum and model-card performance methodology.

### Runtimeها

- [LiteRT-LM Android guide](https://developers.google.com/edge/litert-lm/android) and [overview](https://developers.google.com/edge/litert-lm/overview) — Kotlin API, CPU/GPU/NPU, artifact/setup guidance and Android native-library notes.
- LiteRT-LM `v0.18.0` source: [Conversation.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/Conversation.kt), [ResponseFormat.kt](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/kotlin/java/com/google/ai/edge/litertlm/ResponseFormat.kt), [LICENSE](https://github.com/google-ai-edge/LiteRT-LM/blob/v0.18.0/LICENSE).
- [MediaPipe Android LLM Inference guide](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference/android) — currently labels the API maintenance-only and directs projects to LiteRT-LM. Dependency example: `com.google.mediapipe:tasks-genai:0.10.27`.
- MediaPipe source: [LlmInferenceSession.java](https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/mediapipe/tasks/java/com/google/mediapipe/tasks/genai/llminference/LlmInferenceSession.java), [LlmInference.java](https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/mediapipe/tasks/java/com/google/mediapipe/tasks/genai/llminference/LlmInference.java) (source branch may move; pin a commit before implementation).
- [llama.cpp Android guide](https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md), [OpenCL backend notes](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md), [Android sample JNI/C++ source](https://github.com/ggml-org/llama.cpp/tree/master/examples/llama.android), [MIT license](https://github.com/ggml-org/llama.cpp/blob/master/LICENSE).
- [java-llama.cpp `v5.1.0` root README](https://github.com/bernardladenthin/java-llama.cpp/tree/v5.1.0) — Java API, GGUF, `InferenceParameters.withGrammar()` and JSON-schema→grammar feature.
- [llama-android `v5.1.0` README](https://raw.githubusercontent.com/bernardladenthin/java-llama.cpp/v5.1.0/llama-android/README.md) — Android AAR, minSdk/ABI, no consumer NDK build, optional Adreno OpenCL artifact.
- [llama-kotlin `v5.1.0` README](https://raw.githubusercontent.com/bernardladenthin/java-llama.cpp/v5.1.0/llama-kotlin/README.md) — Flow/cancellation façade and Kotlin 2.3+ consumer requirement; incompatible with this app's current Kotlin 1.9.22 unless a separately approved toolchain change is made.
- [Published Maven POM](https://repo.maven.apache.org/maven2/net/ladenthin/llama-android/5.1.0/llama-android-5.1.0.pom), [AAR binary](https://repo.maven.apache.org/maven2/net/ladenthin/llama-android/5.1.0/llama-android-5.1.0.aar), [Maven metadata](https://repo.maven.apache.org/maven2/net/ladenthin/llama-android/maven-metadata.xml). The AAR was fetched and zip-inspected on 2026-10-13; size/SHA/native entry sizes are recorded in §4.
- [GitHub-hosted runner specifications](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) — public/private Linux standard runner RAM/CPU/disk used for the build-location comparison.

### کد repository مرتبط

- `app/src/main/kotlin/contracts/Contracts.kt`
- `app/src/main/kotlin/inference/InferenceEngine.kt`
- `app/src/main/kotlin/inference/ContextWindowBudgetPolicy.kt`
- `app/src/main/kotlin/inference/ModelDownloadProtocol.kt`
- `app/src/main/kotlin/reasoning/StructuredGrammarConstraint.kt`
- `app/src/main/kotlin/runtime/di/AgentContainer.kt`
- `app/src/main/AndroidManifest.xml`
