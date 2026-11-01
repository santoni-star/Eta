# Повідомлення про треті сторони

## Нативні компоненти терміналу

Система Linux без Root у Eta запускає [PRoot](https://github.com/termux/proot) як окремий процес і статично пов’язана з [talloc](https://talloc.samba.org/) і [libandroid-shmem](https://github.com/termux/libandroid-shmem). Вихідний код PRoot приймає GPL-2.0 або пізнішу версію; talloc приймає LGPL-3.0 або пізнішу версію; libandroid-shmem використовує BSD-3-Clause. Супровідний комбінований виконуваний файл PRoot розповсюджується під GPL-3.0 або пізнішим, і авторські права та ліцензія на окремі компоненти зберігаються.

`assets/native-sources` APK постачається з перевіреним оригінальним вихідним кодом, а також сценаріями, вихідним кодом PTY і пакетами виправлень, автоматично згенерованими з фактичного запису збірки; `assets/licenses` постачається з повним текстом ліцензії. Сценарії збірки та інструкції знаходяться в [Terminal Native Components](TERMINAL_NATIVE.md). Ці незалежні сторонні програми зберігають свої ліцензійні права з відкритим кодом без додаткових обмежень основної некомерційної ліцензії проекту Eta.

## Каталог моделей models.dev

Знімок каталогу автономної моделі Eta надходить із [models.dev](https://models.dev/api.json), а необроблений JSON отримується за допомогою `scripts/update-models-dev-catalog.py` і стискається перед розповсюдженням разом із APK. Дані каталогу та вихідний код models.dev ліцензовані відповідно до [ліцензії MIT](https://github.com/anomalyco/models.dev/blob/dev/LICENSE), авторське право © 2025 models.dev. Повний текст авторських прав і ліцензії включено до APK за адресою [`assets/licenses/models-dev-MIT.txt`](../../app/src/main/assets/licenses/models-dev-MIT.txt).

## Аналіз тексту веб-сторінки

Eta використовує [jsoup](https://jsoup.org/), щоб розібрати завантажений HTML локально. jsoup використовує ліцензію MIT, авторські права належать Джонатану Хедлі; повний текст ліцензії зберігається разом з APK у [`assets/licenses/jsoup-MIT.txt`](../../app/src/main/assets/licenses/jsoup-MIT.txt). Залежна версія підлягає `gradle/libs.versions.toml`.

Стандартна бібліотека Android сумісна зі специфікацією NIO за допомогою [desugar_jdk_libs](https://github.com/google/desugar_jdk_libs), що розповсюджується відповідно до GNU GPL v2 із винятком Classpath, а повний текст ліцензії зберігається в [`assets/licenses/desugar-jdk-libs-LICENSE.txt`](../../app/src/main/assets/licenses/desugar-jdk-libs-LICENSE.txt). Ці компоненти зберігають відповідні ліцензійні права.

## Автентифікація облікового запису

Eta використовує [Nimbus JOSE + JWT](https://connect2id.com/products/nimbus-jose-jwt) для перевірки підпису та підтвердження ідентифікації облікового запису OAuth. Ця бібліотека підтримується Connect2id Ltd. за ліцензією Apache 2.0; повний текст ліцензії зберігається разом із файлом APK за адресою [`assets/licenses/nimbus-jose-jwt-Apache-2.0.txt`](../../app/src/main/assets/licenses/nimbus-jose-jwt-Apache-2.0.txt). Залежна версія підлягає `gradle/libs.versions.toml`.

## Miuix

Інтерфейс програми Eta використовує [Miuix](https://github.com/compose-miuix-ui/miuix), який приймає [Apache License 2.0](https://github.com/compose-miuix-ui/miuix/blob/main/LICENSE).

## DexKit

Eta використовує [DexKit](https://github.com/LuckyPray/DexKit), щоб розібрати функції DEX системної програми та знайти обфусцовану ціль Hook. Залежна версія підлягає `gradle/libs.versions.toml`. Щоб отримати офіційні записи про випуски, перегляньте [Релізи](https://github.com/LuckyPray/DexKit/releases).

Інтерфейс JVM DexKit та інші коди, що не належать до `Core/`, використовують [Ліцензію Apache 2.0](https://github.com/LuckyPray/DexKit/blob/2.2.0/LICENSE), а власний код аналізу `Core/` використовує [GNU LGPL v3](https://github.com/LuckyPray/DexKit/blob/2.2.0/Core/LICENSE). Eta використовує свою незалежну рідну динамічну бібліотеку через офіційний AAR; початковий вихідний код, конфігурація збірки та заява про авторські права доступні за адресою [Офіційний вихідний код](https://github.com/LuckyPray/DexKit/tree/2.2.0). Ці сторонні компоненти зберігають оригінальні ліцензійні права та не підлягають додатковим обмеженням некомерційної ліцензії основного проекту Eta.

## JetBrains Mono

Інтерфейс терміналу використовує безлігатурну версію (звичайний, жирний) [JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) 2.304, що поширюється разом із APK як ресурси шрифтів. Цей шрифт ліцензовано відповідно до ліцензії SIL Open Font License 1.1, авторське право на яку належать авторам проекту JetBrains Mono; повний текст ліцензії зберігається разом з APK у [`assets/licenses/jetbrains-mono-OFL.txt`](../../app/src/main/assets/licenses/jetbrains-mono-OFL.txt).

## Матеріальні іконки

Значки функцій Eta використовують Rounded серію AndroidX Compose Material Icons, надану через залежність `material-icons-extended`. Піктограма та її реалізація AndroidX використовують [Ліцензію Apache 2.0](https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt), а джерело можна знайти в [Значки матеріального дизайну](https://github.com/google/material-design-icons).

## Іконка застосунку

Піктограма програми Eta взята з іконки програми Google [AI Edge Gallery](https://github.com/google-ai-edge/gallery), перетворена на VectorDrawable і зберігає безпечний простір, а піктограма сповіщення є її монохромним варіантом. AI Edge Gallery ліцензовано відповідно до [ліцензії Apache 2.0](https://github.com/google-ai-edge/gallery/blob/main/LICENSE).

## Lucide Atom

Значок мислення використовує [Lucide Atom](https://github.com/lucide-icons/lucide/blob/main/icons/atom.svg), який зберігається як локальний ресурс VectorDrawable і не покладається на бібліотеку піктограм Lucide. Ця піктограма ліцензована за ліцензією ISC:

```text
ISC License

Copyright (c) 2026 Lucide Icons and Contributors

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
```

## Android Hidden API Bypass

Eta використовує [Android Hidden API Bypass](https://github.com/LSPosed/AndroidHiddenApiBypass), щоб застосувати вибрані користувачем налаштування прогнозного повернення. Ця бібліотека ліцензована згідно з [ліцензією Apache 2.0](https://github.com/LSPosed/AndroidHiddenApiBypass/blob/main/LICENSE).

## Lobe Icons

Значки брендингу моделі та постачальника з
[Значки лоба](https://github.com/lobehub/lobe-icons)
`@lobehub/icons-static-avatar` 1.13.0. Оригінальний матеріал WebP 1280 × 1280 без втрат масштабується до 128 × 128 без зміни кольору та пропорцій, а потім локально упаковується Eta.

| Ресурси Eta | Лоб іконки Аватар |
| --- | --- |
| `provider_logo_openai.webp` | [[КОД1]] |
| `provider_logo_anthropic.webp` | [[КОД1]] |
| `provider_logo_bailian.webp` | [[КОД1]] |
| `provider_logo_deepseek.webp` | [[КОД1]] |
| `provider_logo_kimi.webp` | [[КОД1]] |
| `provider_logo_mimo.webp` | [[КОД1]] |
| `provider_logo_minimax.webp` | [[КОД1]] |
| `provider_logo_stepfun.webp` | [[КОД1]] |
| `provider_logo_siliconflow.webp` | [[КОД1]] |
| `provider_logo_openrouter.webp` | [[КОД1]] |
| `model_logo_claude.webp` | [[КОД1]] |
| `model_logo_qwen.webp` | [[КОД1]] |
| `model_logo_zai.webp` | [[КОД1]] |
| `model_logo_chatglm.webp` | [[КОД1]] |
| `model_logo_gemini.webp` | [[КОД1]] |
| `model_logo_gemma.webp` | [[КОД1]] |
| `model_logo_grok.webp` | [[КОД1]] |
| `model_logo_meta.webp` | [[КОД1]] |
| `model_logo_mistral.webp` | [[КОД1]] |
| `model_logo_doubao.webp` | [[КОД1]] |
| `model_logo_hunyuan.webp` | [[КОД1]] |
| `model_logo_yi.webp` | [[КОД1]] |

Lobe Icons використовує ліцензію MIT:

```text
MIT License

Copyright (c) 2023 LobeHub

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Іконка Kimi Code

`ic_kimi_code.xml` запису «Запустити Kimi Web» на домашній сторінці перетворено з Moonshot AI
[Код Кімі](https://github.com/MoonshotAI/kimi-code/blob/d1ca65e1de189617e9edbc54010e62d472a1de3d/apps/vscode/resources/kimi-icon.svg)
суцільного кольору бічній панелі піктограм. Перетворення адаптується лише до формату Android VectorDrawable, зберігаючи оригінальну геометрію та пропорції.

Kimi Code використовує ліцензію MIT:

```text
MIT License

Copyright (c) 2026 Moonshot AI

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

Назви, значки та інші торгові марки OpenAI, Anthropic, Claude, Alibaba Cloud Bailian, Qwen, DeepSeek, Kimi, Xiaomi MiMo, MiniMax, StepFun, Z.ai, ChatGLM, Gemini, Gemma, Grok, Meta, Mistral, Doubao, Hunyuan, Yi, Silicon Mobile і OpenRouter належать відповідним правовласникам. Eta відображає ці піктограми лише для точної ідентифікації модельної служби, яку налаштовує користувач, і не означає спонсорство, підтримку чи партнерство з Eta з боку цих постачальників. Використання піктограми OpenAI також має відповідати його [Рекомендаціям бренду](https://openai.com/brand/).

## Додаткова мова виконання

Eta не пакує uv або Node.js в APK. Коли користувач активно встановлює відповідний профіль, Eta завантажує останню офіційну версію поточного виправленого продукту; Debian використовує офіційний дистрибутивний пакет glibc Linux Node.js, Alpine використовує `nodejs-current` у своєму стабільному сховищі, а uv завантажує офіційний продукт glibc або musl відповідно до вибраного дистрибутива.

| Інструменти | Джерела | Ліцензія |
| --- | --- | --- |
| uv / uvx | [astral-sh/uv](https://github.com/astral-sh/uv) | Ліцензія Apache 2.0 / MIT |
| Node.js / npm / npx | [nodejs/вузол](https://github.com/nodejs/node) | Ліцензії MIT і сторонніх розробників у дистрибутивних пакетах |

## Додатковий інструмент аналізу APK

Eta не упаковує такі інструменти в APK. Коли користувач активно встановлює «APK Analysis» на сторінці середовища інструменту Linux, Eta завантажує та перевіряє продукт із виправленої офіційної версії; інструмент зберігається в середовищі Alpine або Debian, вибраному користувачем, і застосовуються відповідні ліцензії:

| Інструменти | Джерела | Ліцензія |
| --- | --- | --- |
| JADX | [skylot/jadx](https://github.com/skylot/jadx) | Ліцензія Apache 2.0 |
| Apktool | [iBotPeaches/Apktool](https://github.com/iBotPeaches/Apktool) | Ліцензія Apache 2.0 |
| smali/baksmali | [google/smali](https://github.com/google/smali) | Ліцензія BSD 3-Clause |

Ліцензія на розповсюдження для JADX зберігається з необхідними файлами CLI; ліцензії та повідомлення третіх сторін для Apktool, smali та baksmali зберігаються у відповідних артефактах JAR. Eta забезпечує лише перевірене встановлення, введення команд і обмеження можливостей і не авторизує ці інструменти повторно.

Коли фактичний домен артефакту GitHub недоступний або повільний, інсталятор може запитати ту саму публічну URL-адресу випуску через `gh-proxy.com`; Артефакти Node.js можна спочатку запитати через `cdn.npmmirror.com`. Портал завантаження знатиме мережеву адресу користувача та запитуваний загальнодоступний продукт; Eta не надсилатиме йому номер облікового запису, файли cookie, ключ API чи інші дані Eta та продовжуватиме перевіряти вбудований офіційний розмір продукту та SHA-256 перед розміщенням замовлення. Користувачі, які не бажають використовувати портал завантаження, можуть не встановлювати відповідні додаткові файли.
