package com.hybridauth.skin;

import com.hybridauth.skin.SkinRequestRules.UrlProblem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkinRequestRulesTest {

    private static final List<String> ANY = List.of();

    @Test
    void parseUrlArgs_variantAfterLink() {
        SkinRequestRules.UrlArgs args = SkinRequestRules.parseUrlArgs("https://i.imgur.com/a.png slim");
        assertEquals("https://i.imgur.com/a.png", args.url());
        assertEquals(SkinVariant.SLIM, args.variant());
    }

    @Test
    void parseUrlArgs_noVariantMeansAuto() {
        SkinRequestRules.UrlArgs args = SkinRequestRules.parseUrlArgs("  https://i.imgur.com/a.png  ");
        assertEquals("https://i.imgur.com/a.png", args.url());
        assertEquals(SkinVariant.AUTO, args.variant());
    }

    @Test
    void parseUrlArgs_unknownLastWordStaysInLink() {
        SkinRequestRules.UrlArgs args = SkinRequestRules.parseUrlArgs("https://example.com/a b");
        assertEquals("https://example.com/a b", args.url());
        assertEquals(SkinVariant.AUTO, args.variant());
    }

    @Test
    void parseUrlArgs_onlyVariantWordIsNotALinkWithVariant() {
        // «slim» без ссылки: ссылка пуста — отсекается проверкой ссылки, а не разбором
        SkinRequestRules.UrlArgs args = SkinRequestRules.parseUrlArgs("slim");
        assertEquals("slim", args.url());
        assertEquals(Optional.of(UrlProblem.BAD_SCHEME), SkinRequestRules.checkUrl(args.url(), ANY));
    }

    @Test
    void checkUrl_acceptsPublicHttps() {
        assertEquals(Optional.empty(), SkinRequestRules.checkUrl("https://i.imgur.com/skin.png", ANY));
        assertEquals(Optional.empty(), SkinRequestRules.checkUrl("http://example.org/a/b.png?x=1", ANY));
    }

    @Test
    void checkUrl_rejectsEmptyAndLong() {
        assertEquals(Optional.of(UrlProblem.EMPTY), SkinRequestRules.checkUrl("  ", ANY));
        String longUrl = "https://example.com/" + "a".repeat(SkinRequestRules.MAX_URL_LENGTH);
        assertEquals(Optional.of(UrlProblem.TOO_LONG), SkinRequestRules.checkUrl(longUrl, ANY));
    }

    @Test
    void checkUrl_rejectsOtherSchemes() {
        assertEquals(Optional.of(UrlProblem.BAD_SCHEME), SkinRequestRules.checkUrl("file:///etc/passwd", ANY));
        assertEquals(Optional.of(UrlProblem.BAD_SCHEME), SkinRequestRules.checkUrl("ftp://example.com/a.png", ANY));
        assertEquals(Optional.of(UrlProblem.BAD_SCHEME), SkinRequestRules.checkUrl("example.com/a.png", ANY));
    }

    @Test
    void checkUrl_rejectsLocalAndLiteralHosts() {
        for (String url : List.of(
                "http://localhost/a.png",
                "http://127.0.0.1/a.png",
                "http://192.168.1.10/a.png",
                "http://[::1]/a.png",
                "http://intranet/a.png",
                "http://printer.local/a.png",
                "http://service.internal/a.png")) {
            assertEquals(Optional.of(UrlProblem.BAD_HOST), SkinRequestRules.checkUrl(url, ANY), url);
        }
    }

    @Test
    void checkUrl_allowedDomains() {
        List<String> allowed = List.of("i.imgur.com", "*.discordapp.com");
        assertEquals(Optional.empty(), SkinRequestRules.checkUrl("https://i.imgur.com/a.png", allowed));
        assertEquals(Optional.empty(), SkinRequestRules.checkUrl("https://cdn.discordapp.com/a.png", allowed));
        assertEquals(Optional.of(UrlProblem.DOMAIN_NOT_ALLOWED),
                SkinRequestRules.checkUrl("https://evil.example.com/a.png", allowed));
        // Домен не совпадает по суффиксу без точки
        assertEquals(Optional.of(UrlProblem.DOMAIN_NOT_ALLOWED),
                SkinRequestRules.checkUrl("https://notdiscordapp.com/a.png", allowed));
    }

    @Test
    void cooldown() {
        assertEquals(0, SkinRequestRules.cooldownRemainingSeconds(0, 1_000_000, 30));
        assertEquals(0, SkinRequestRules.cooldownRemainingSeconds(1_000, 500_000, 0));
        assertEquals(30, SkinRequestRules.cooldownRemainingSeconds(1_000_000, 1_000_000, 30));
        assertEquals(10, SkinRequestRules.cooldownRemainingSeconds(1_000_000, 1_020_000, 30));
        assertEquals(1, SkinRequestRules.cooldownRemainingSeconds(1_000_000, 1_029_500, 30));
        assertEquals(0, SkinRequestRules.cooldownRemainingSeconds(1_000_000, 1_030_000, 30));
        assertTrue(SkinRequestRules.cooldownRemainingSeconds(1_000_000, 900_000, 30) > 0);
    }
}
