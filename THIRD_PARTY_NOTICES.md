# Third-party notices

## SkinRestorer

The packet sequence that refreshes a player's skin on the server (`com.hybridauth.skin.SkinRefresher`, and the
`ChunkMapAccessor` / `TrackedEntityAccessor` mixins it needs) is adapted from SkinRestorer:
https://github.com/Suiranoil/SkinRestorer

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

The rest of the skin module (storage, Mojang and MineSkin clients, commands) is original to HybridAuth. MineSkin
(https://mineskin.org) and the Mojang session server are used through their public HTTP APIs.
