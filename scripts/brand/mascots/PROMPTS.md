# Зверята Kinsight — промпты для ChatGPT

Нужно 10 картинок: по одной на зверька, каждая — **сетка 3 × 2 из шести настроений**. Одна картинка на зверька, а не 60 отдельных, — так все настроения получаются в одном стиле и с одной мордочкой.

## Как работать

1. Открой новый чат в ChatGPT (модель с генерацией картинок).
2. Отправь **один раз** блок «Стиль» целиком.
3. Затем по одному отправляй промпты зверьков (CAT, DOG, …). После каждого скачивай PNG.
4. Сохрани файлы под именами `cat.png`, `dog.png`, `fox.png`, `panda.png`, `rabbit.png`, `bear.png`, `owl.png`, `penguin.png`, `frog.png`, `raccoon.png` в одну папку.
5. Если зверёк вышел не в стиле остальных — попроси «повтори в точности в стиле предыдущих, тот же масштаб и толщина линий».
6. Нарежь и положи в приложение (см. конец файла).

Проверяй каждую сетку: порядок настроений слева направо, сверху вниз — **счастлив, доволен, спокоен / волнуется, расстроен, ему плохо**; голова по центру клетки, одинакового размера во всех шести клетках; фон прозрачный.

---

## Стиль (отправить первым сообщением)

```
I am making avatar icons for a family app for kids and parents. I will ask you for 10 animals, one image per animal. Keep EXACTLY the same style for all of them:

- Flat vector illustration, cute and friendly, like a modern sticker or app emoji. Soft rounded shapes, no outlines or very thin dark outlines, gentle flat shading at most, no gradients-heavy 3D, no text, no letters.
- Only the animal's HEAD and face, front view, centered, filling about 80% of each cell, so it looks good cropped into a circle.
- Each image is a grid of 6 equal square cells: 3 columns × 2 rows, with clear empty space between cells. The same animal in every cell, same size and position; only the facial expression changes.
- Order of moods, left to right, top row then bottom row:
  1. very happy — eyes closed in happy arcs, big open smile, pink cheeks
  2. content — open dot eyes, small gentle smile
  3. calm — open dot eyes, neutral straight mouth
  4. worried — eyebrows raised and slanted, small wavy mouth
  5. upset — sad eyes, downturned mouth, one tear
  6. not feeling well — droopy half-closed eyes, deep frown, tears, a slightly greenish sick tint on the face, a sweat drop
- Fully transparent background (PNG with alpha). No circles, frames, shadows or backgrounds behind the heads.
- Square image, 1536 × 1024 px (each cell 512 × 512).

Reply "ready" and wait for the first animal.
```

## Зверьки (отправлять по одному)

```
CAT: an orange tabby cat with pointed ears, pink inner ears, white muzzle and a few stripes on the forehead, little whiskers.
```
```
DOG: a light-brown puppy with long floppy darker ears, cream muzzle, black round nose.
```
```
FOX: an orange fox with pointed ears with dark tips, white cheeks and muzzle, black nose.
```
```
PANDA: a giant panda, white face, black round ears and black eye patches, black nose.
```
```
RABBIT: a white bunny with long upright ears with pink inner ears, pink nose.
```
```
BEAR: a brown teddy bear with round ears, lighter tan muzzle, dark nose.
```
```
OWL: a brown owl with big round light eye discs, small ear tufts, orange beak.
```
```
PENGUIN: a penguin with a black head and a white heart-shaped face, small orange beak.
```
```
FROG: a green frog with big eyes on top of the head, wide mouth, lighter belly-green chin.
```
```
RACCOON: a grey raccoon with a dark mask band across the eyes, white muzzle and eyebrows, pointed ears.
```

---

## Нарезка и подключение

Нужен Python 3 и Pillow (один раз):

```bash
python3 -m pip install --user pillow
```

Нарезать все 10 сеток и положить картинки в приложение:

```bash
python3 scripts/brand/mascots/slice_mascots.py ~/Downloads/mascots
```

Скрипт режет каждую сетку на 6 клеток, обрезает пустые края, выравнивает голову по центру квадрата и сохраняет `core/designsystem/src/main/res/drawable-nodpi/mascot_<зверёк>_<настроение>.webp` (256 × 256, ~10–20 КБ каждая, всего около 1 МБ). Если какой-то сетки не хватает или клетка пустая — скрипт скажет, какой именно, и ничего не запишет для этого зверька.

Скрипт также обновляет `MascotDrawables.kt`: явный `when` со ссылками на существующие WebP. Для отсутствующих ресурсов возвращается `null`, и `AnimalAvatar` рисует векторный вариант. После удаления WebP перезапусти скрипт, чтобы обновить сопоставление. Все шесть настроений одного зверька нарезаются в одном масштабе с сохранением исходной прозрачности.
