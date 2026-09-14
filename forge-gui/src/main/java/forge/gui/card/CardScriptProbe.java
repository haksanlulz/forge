package forge.gui.card;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardSplitType;
import forge.card.CardStateName;
import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.card.Card;
import forge.game.card.CardFactory;
import forge.game.card.CardState;
import forge.game.replacement.ReplacementEffect;
import forge.game.trigger.Trigger;
import forge.item.PaperCard;

/**
 * Validates a card script the way a game would consume it, before the Workshop writes it to disk.
 * No UI in here so it can run headless (and under test).
 */
public final class CardScriptProbe {
    private CardScriptProbe() {
    }

    /** The script split into the lines the card reader would see: it trims every line before parsing it. */
    public static List<String> scriptLines(final String text) {
        // CardStorageReader.readScript -> FileUtil.readAllLines(reader, true): validate what the game will run,
        // or an indented "  A:AB$ Bogus" parses here as key "  A" (ignored) and as a real ability on the next start
        final List<String> lines = new ArrayList<>();
        for (final String line : text.split("\r?\n")) {
            lines.add(line.trim());
        }
        return lines;
    }

    /** Parses a script the way the card reader does, so {@code normalizedName} is the file stem. */
    public static CardRules parseRules(final String text, final String stem) {
        return new CardRules.Reader().readCard(scriptLines(text), stem);
    }

    /**
     * True when the script takes a face from another card ({@code CopyFaceFrom:}); such a rules
     * object has no face of its own until the database supplies it, so it cannot be probed here.
     */
    public static boolean usesCopyFaceFrom(final String text) {
        for (final String line : scriptLines(text)) {
            if (line.startsWith("CopyFaceFrom:")) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when every face the rules need is present, so {@code getName()} and the card probe
     * are safe to call: a split/aftermath script with no second face (or one that borrows it)
     * would otherwise fail on the missing face rather than on a message.
     */
    public static boolean hasAllFaces(final CardRules rules) {
        if (rules.getMainPart() == null) {
            return false;
        }
        return rules.getSplitType().getAggregationMethod() != CardSplitType.FaceSelectionMethod.COMBINE
                || rules.getOtherPart() != null;
    }

    /**
     * Builds a real {@link Card} from the rules exactly as a game would (id 0, so SVars, abilities,
     * triggers, replacements and statics are all parsed - a negative id skips them), then forces every
     * trigger and replacement effect to resolve its {@code Execute} / {@code ReplaceWith} ability,
     * which a game otherwise does lazily the first time it fires. Anything the script would crash a
     * game with surfaces here as the exception a game would have thrown.
     * <p>
     * The card is built inside a throwaway, playerless {@link Game}: the view update that runs at
     * the end of card construction evaluates mana abilities such as
     * {@code Produced$ Special EachColorAmong_Valid ...} against the battlefield, and with no game
     * at all that is a NullPointerException on a perfectly valid stock card (Bloom Tender,
     * Faeburrow Elder, Tarnation Vista - measured 2026-09-14 over the whole card pool).
     */
    public static void probeCard(final CardRules rules, final String edition, final CardRarity rarity) {
        final GameRules gameRules = new GameRules(GameType.Constructed);
        final Game game = new Game(Collections.emptyList(), gameRules, new Match(gameRules, Collections.emptyList(), "Workshop probe"));
        final Card probe = CardFactory.getCard(new PaperCard(rules, edition, rarity), null, 0, game);
        for (final CardStateName stateName : probe.getStates()) {
            final CardState state = probe.getState(stateName);
            if (state == null) {
                continue;
            }
            for (final Trigger trigger : state.getTriggers()) {
                trigger.ensureAbility(state);
            }
            for (final ReplacementEffect replacement : state.getReplacementEffects()) {
                replacement.ensureAbility();
            }
        }
    }
}
