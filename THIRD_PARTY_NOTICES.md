# Сторонний код и лицензии

## SkinRestorer

Последовательность пакетов, которая показывает игроку новый скин на работающем сервере
(`com.hybridauth.skin.SkinRefresher` и два миксина-акцессора `ChunkMapAccessor` и `TrackedEntityAccessor`),
адаптирована из мода SkinRestorer: <https://github.com/Suiranoil/SkinRestorer>. Он распространяется по лицензии MIT,
текст лицензии приведён ниже в оригинале, как того требует сама лицензия.

```
MIT License

Copyright (c) 2021-2024 Lionarius

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

## Что написано заново

Остальной модуль скинов (хранилище, клиенты Mojang и MineSkin, команды) — собственный код HybridAuth.
MineSkin (<https://mineskin.org>) и сервер сессий Mojang используются через их публичные HTTP API.
