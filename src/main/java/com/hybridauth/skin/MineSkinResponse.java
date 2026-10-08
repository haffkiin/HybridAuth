package com.hybridauth.skin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Разбор ответов MineSkin API v2 ({@code POST /v2/queue}, {@code GET /v2/queue/{id}},
 * {@code GET /v2/skins/{uuid}}). Только JSON, без сетевого кода.
 *
 * @param kind     что делать дальше
 * @param jobId    идентификатор задачи для опроса ({@link Kind#PENDING})
 * @param skinUuid UUID готового скина в MineSkin ({@link Kind#SKIN_LOOKUP})
 * @param property готовые текстуры ({@link Kind#COMPLETED})
 * @param message  причина отказа ({@link Kind#FAILED}) или подсказка
 */
public record MineSkinResponse(Kind kind, String jobId, String skinUuid, SkinProperty property, String message) {

    public enum Kind {
        /** Скин готов, текстуры в ответе. */
        COMPLETED,
        /** Задача в очереди, нужно опрашивать. */
        PENDING,
        /** Задача завершена, но текстур в ответе нет: загрузить скин по UUID. */
        SKIN_LOOKUP,
        FAILED,
        RATE_LIMITED,
        /** Ключ не принят. */
        AUTH_ERROR
    }

    public static MineSkinResponse parse(int httpStatus, String body) {
        if (httpStatus == 429) {
            return of(Kind.RATE_LIMITED, "лимит MineSkin");
        }
        if (httpStatus == 401 || httpStatus == 403) {
            return of(Kind.AUTH_ERROR, "ключ MineSkin не принят");
        }

        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            return of(Kind.FAILED, "непонятный ответ MineSkin (код " + httpStatus + ")");
        }

        SkinProperty property = extractProperty(root);
        if (property != null) {
            return new MineSkinResponse(Kind.COMPLETED, null, null, property, null);
        }

        boolean explicitFailure = root.has("success") && !root.get("success").isJsonNull()
                && !root.get("success").getAsBoolean();
        if (httpStatus >= 400 || explicitFailure) {
            return of(Kind.FAILED, errorMessage(root, httpStatus));
        }

        if (root.has("job") && root.get("job").isJsonObject()) {
            JsonObject job = root.getAsJsonObject("job");
            String status = job.has("status") ? job.get("status").getAsString() : "";
            String id = job.has("id") ? job.get("id").getAsString() : null;
            switch (status) {
                case "failed" -> {
                    return of(Kind.FAILED, errorMessage(root, httpStatus));
                }
                case "completed" -> {
                    String result = job.has("result") && !job.get("result").isJsonNull()
                            ? job.get("result").getAsString()
                            : null;
                    return result == null
                            ? of(Kind.FAILED, "задача завершена без результата")
                            : new MineSkinResponse(Kind.SKIN_LOOKUP, id, result, null, null);
                }
                default -> {
                    return id == null
                            ? of(Kind.FAILED, "нет идентификатора задачи")
                            : new MineSkinResponse(Kind.PENDING, id, null, null, null);
                }
            }
        }
        return of(Kind.FAILED, "в ответе MineSkin нет данных о скине");
    }

    private static MineSkinResponse of(Kind kind, String message) {
        return new MineSkinResponse(kind, null, null, null, message);
    }

    /** Текстуры из {@code skin.texture.data}; ответ {@code /v2/skins/{uuid}} бывает и без обёртки {@code skin}. */
    private static SkinProperty extractProperty(JsonObject root) {
        JsonObject skin = null;
        if (root.has("skin") && root.get("skin").isJsonObject()) {
            skin = root.getAsJsonObject("skin");
        } else if (root.has("texture") && root.get("texture").isJsonObject()) {
            skin = root;
        }
        if (skin == null || !skin.has("texture") || !skin.get("texture").isJsonObject()) {
            return null;
        }
        JsonObject texture = skin.getAsJsonObject("texture");
        if (!texture.has("data") || !texture.get("data").isJsonObject()) {
            return null;
        }
        JsonObject data = texture.getAsJsonObject("data");
        if (!data.has("value") || data.get("value").isJsonNull()) {
            return null;
        }
        String signature = data.has("signature") && !data.get("signature").isJsonNull()
                ? data.get("signature").getAsString()
                : null;
        try {
            return new SkinProperty(data.get("value").getAsString(), signature);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String errorMessage(JsonObject root, int httpStatus) {
        if (root.has("errors") && root.get("errors").isJsonArray()) {
            JsonArray errors = root.getAsJsonArray("errors");
            for (JsonElement element : errors) {
                if (element.isJsonObject()) {
                    JsonObject error = element.getAsJsonObject();
                    if (error.has("message")) {
                        return error.get("message").getAsString();
                    }
                    if (error.has("code")) {
                        return error.get("code").getAsString();
                    }
                } else if (element.isJsonPrimitive()) {
                    return element.getAsString();
                }
            }
        }
        return "MineSkin отклонил запрос (код " + httpStatus + ")";
    }
}
