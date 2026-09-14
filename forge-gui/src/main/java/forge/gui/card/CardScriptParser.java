package forge.gui.card;

import com.google.common.collect.ImmutableSortedSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import forge.card.CardType;
import forge.card.MagicColor;
import forge.game.ability.AbilityFactory;
import forge.game.ability.AbilityFactory.AbilityRecordType;
import forge.game.ability.ApiType;
import forge.game.replacement.ReplacementType;
import forge.game.trigger.TriggerType;
import forge.util.TextUtil;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class CardScriptParser {

    private final String script;
    private final Set<String> sVars = Sets.newTreeSet(), sVarAbilities = Sets.newTreeSet();
    public CardScriptParser(final String script) {
        this.script = script;

        final String[] lines = StringUtils.split(script, "\r\n");
        for (final String line : lines) {
            if (StringUtils.isEmpty(line)) {
                continue;
            }
            if (line.startsWith("SVar:")) {
                // limit 3: the value may itself hold colons (KW$ Protection:Card.Red, a Picture URL)
                final String[] sVarParts = line.split(":", 3);
                if (sVarParts.length != 3) {
                    continue;
                }
                sVars.add(sVarParts[1]);
            }
        }
    }

    public Map<Integer, Integer> getErrorRegions() {
        return getErrorRegions(false);
    }

    /**
     * Find all erroneous regions of this script.
     *
     * @param quick
     *            if {@code true}, stop when the first region is found.
     * @return a {@link Map} mapping the starting index of each error region to
     *         the length of that region.
     */
    private Map<Integer, Integer> getErrorRegions(final boolean quick) {
        final Map<Integer, Integer> result = Maps.newTreeMap();

        // keep empty lines: every line, empty or not, advances the region index by its length + 1
        final String[] lines = script.split("\n", -1);
        int index = 0;
        for (final String line : lines) {
            final String trimLine = line.trim();
            if (StringUtils.isEmpty(trimLine)) {
                index += line.length() + 1;
                continue;
            }
            boolean bad = false;
            if (trimLine.startsWith("Name:") && trimLine.length() > "Name:".length()) {
                // whatever, nonempty name is always ok!
            } else if (trimLine.startsWith("ManaCost:")) {
                if (!isManaCostLegal(trimLine.substring("ManaCost:".length()))) {
                    bad = true;
                }
            } else if (trimLine.startsWith("Types:")) {
                if (!isTypeLegal(trimLine.substring("Types:".length()))) {
                    bad = true;
                }
            } else if (trimLine.startsWith("A:")) {
                // TODO check if it's non-permanent, then Cost$ isn't mandatory
                result.putAll(getActivatedAbilityErrors(trimLine.substring("A:".length()), index + "A:".length()));
            } else if (trimLine.startsWith("R:")) {
                result.putAll(getReplacementErrors(trimLine.substring("R:".length()), index + "R:".length()));
            } else if (trimLine.startsWith("S:")) {
                // TODO
            } else if (trimLine.startsWith("T:")) {
                result.putAll(getTriggerErrors(trimLine.substring("T:".length()), index + "T:".length()));
            } else if (trimLine.startsWith("SVar:")) {
                final String[] sVarParts = trimLine.split(":", 3);
                if (sVarParts.length != 3) {
                    bad = true;
                }
                if (sVarAbilities.contains(sVarParts[1])) {
                    result.putAll(getSubAbilityErrors(sVarParts[2], index + "SVar:".length() + 1 + sVarParts[1].length() + 1));
                }
            }
            if (bad) {
                result.put(index, trimLine.length());
            }
            index += line.length() + 1;
            if (quick && !result.isEmpty()) {
                break;
            }
        }
        return result;
    }

    private static boolean isManaCostLegal(final String manaCost) {
        if (manaCost.equals("no cost")) {
            return true;
        }
        if (StringUtils.isEmpty(manaCost) || StringUtils.isWhitespace(manaCost)) {
            return false;
        }

        for (final String part : StringUtils.split(manaCost, ' ')) {
            if (!isManaCostPart(part)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One space-separated part of a mana cost as the scripts write it: a generic amount, X/Y/Z, a
     * single symbol, or a hybrid shard written as its letters ({@code WU}, {@code 2R}, {@code BP} for
     * Phyrexian, {@code 2/W}). Shape check only; ManaCost owns the grammar.
     */
    private static boolean isManaCostPart(final String part) {
        if (StringUtils.isNumeric(part) || part.equals("X") || part.equals("Y") || part.equals("Z")) {
            return true;
        }
        if (part.isEmpty() || part.length() > 4) {
            return false;
        }
        boolean symbol = false;
        for (final char c : part.toCharArray()) {
            if (isManaSymbol(c)) {
                symbol = true;
            } else if (c != 'P' && c != '2' && c != '/') {
                return false;
            }
        }
        return symbol;
    }
    private static boolean isManaSymbol(final char c) {
        return c == 'W' || c == 'U' || c == 'B' || c == 'R' || c == 'G' || c == 'S' || c == 'C';
    }

    private static boolean isTypeLegal(final String type) {
        // walk the line as CardType.parse does: a multi-word type ("Time Lord", "Serra's Realm") first, else one word
        int start = 0;
        while (start < type.length()) {
            final String rest = type.substring(start);
            String t = null;
            for (final String multi : CardType.Constant.MultiwordTypes) {
                if (rest.startsWith(multi)) {
                    t = multi;
                    break;
                }
            }
            if (t == null) {
                final int space = rest.indexOf(' ');
                t = space < 0 ? rest : rest.substring(0, space);
            }
            if (!t.isEmpty() && !isSingleTypeLegal(t)) {
                return false;
            }
            start += t.length() + 1;
        }
        return true;
    }
    private static boolean isSingleTypeLegal(final String type) {
        return CardType.isACardType(type) || CardType.isASupertype(type) || CardType.isASubType(type);
    }

    private static List<KeyValuePair> getParams(final String ability, final int offset, final Map<Integer, Integer> errorRegions) {
        final String[] parts = StringUtils.split(ability, '|');
        final List<KeyValuePair> params = Lists.newArrayList();
        int currentIndex = offset;
        for (final String part : parts) {
            // first '$' only, as FileSection.parseToMap splits: a value may hold its own (Count$..., TriggerCount$...)
            final int dollar = part.indexOf('$');
            if (StringUtils.isBlank(part)) {
                errorRegions.put(currentIndex, part.length());
            } else if (dollar < 0) {
                params.add(new KeyValuePair(part, "", currentIndex));
            } else {
                params.add(new KeyValuePair(part.substring(0, dollar), part.substring(dollar + 1), currentIndex));
            }
            currentIndex += part.length() + 1;
        }

        // Check spacing
        for (final KeyValuePair param : params) {
            if (!param.getKey().startsWith(" ") && param.startIndex() != offset) {
                errorRegions.put(param.startIndex() - 1, 2);
            }
            if (!param.getValue().startsWith(" ")) {
                errorRegions.put(param.startIndexValue() - 1, 2);
            }
            if (!param.getValue().endsWith(" ") && param.endIndex() != offset + ability.length()) {
                errorRegions.put(param.endIndex() - 1, 2);
            }
        }
        return params;
    }

    private Map<Integer, Integer> getActivatedAbilityErrors(final String ability, final int offset) {
        return getAbilityErrors(ability, offset, true);
    }
    private Map<Integer, Integer> getSubAbilityErrors(final String ability, final int offset) {
        return getAbilityErrors(ability, offset, false);
    }
    private Map<Integer, Integer> getAbilityErrors(final String ability, final int offset, final boolean topLevel) {
        final Map<Integer, Integer> result = Maps.newTreeMap();
        final List<KeyValuePair> params = getParams(ability, offset, result);
        if (params.isEmpty()) {
            // "A:" with nothing after it (or an SVar ability left empty): no declarer to check, nothing to index into
            result.put(offset, Math.max(1, ability.length()));
            return result;
        }

        // First parameter should be Api declaration
        final String declarer = params.get(0).getKey().trim();
        if (!isAbilityApiDeclarerLegal(declarer)) {
            result.put(params.get(0).startIndex(), params.get(0).length());
        }
        // An activated or static ability needs a Cost somewhere in its parameters; a spell (SP) falls back
        // to the card's mana cost and a sub-ability (DB) never has one (AbilityFactory.parseAbilityCost).
        if (topLevel && !declarer.equals(AbilityRecordType.Spell.getPrefix())
                && params.stream().noneMatch(p -> p.getKey().trim().equals("Cost"))) {
            result.put(params.get(0).startIndex(), params.get(0).length());
        }

        // Now, check the parameters whose vocabulary is known. Every other key is left alone: each
        // ApiType reads its own parameters and there is no registry of them, so an unrecognized key
        // is not evidence of an error.
        for (final KeyValuePair param : params) {
            boolean isBadValue = false;
            final String trimKey = param.getKey().trim(), trimValue = param.getValue().trim();
            if (isAbilityApiDeclarerLegal(trimKey)) {
                if (!isAbilityApiLegal(trimValue)) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("Cost")) {
                if (!isCostLegal(trimValue)) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("ValidTgts")) {
                if (!isValidLegal(trimValue)) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("ValidCards")) {
                // a ValidCards list may also name a Defined set (Remembered, Targeted, ...)
                if (!isValidLegal(trimValue) && !isDefinedLegal(trimValue)) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("Defined")) {
                if (!isDefinedLegal(trimValue)) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("TgtPrompt") || trimKey.equals("TargetMin") || trimKey.equals("TargetMax")
                    || trimKey.equals("AILogic") || trimKey.equals("StackDescription") || trimKey.equals("SpellDescription")) {
                if (trimValue.isEmpty()) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("SubAbility") || AbilityFactory.additionalAbilityKeys.contains(trimKey)) {
                if (sVars.contains(trimValue)) {
                    sVarAbilities.add(trimValue);
                } else {
                    isBadValue = true;
                }
            }
            if (isBadValue) {
                result.put(param.startIndexValue(), param.valueLength());
            }
        }
        return result;
    }

    private Map<Integer, Integer> getReplacementErrors(final String replacement, final int offset) {
        final Map<Integer, Integer> result = Maps.newTreeMap();
        final List<KeyValuePair> params = getParams(replacement, offset, result);

        // Check all parameters
        for (final KeyValuePair param : params) {
            boolean isBadValue = false;
            final String trimKey = param.getKey().trim(), trimValue = param.getValue().trim();
            if (trimKey.equals("Event")) {
                if (!isReplacementApiLegal(trimValue)) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("ReplaceWith")) {
                if (sVars.contains(trimValue)) {
                    sVarAbilities.add(trimValue);
                } else {
                    isBadValue = true;
                }
            } else if (trimKey.equals("Description")) {
                if (trimValue.isEmpty()) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("ValidCard")) {
                if (!isValidLegal(trimValue) && !isDefinedLegal(trimValue)) {
                    isBadValue = true;
                }
            }
            // other keys are the replacement type's own parameters: no registry, so no verdict
            if (isBadValue) {
                result.put(param.startIndexValue(), param.valueLength());
            }
        }
        return result;
    }

    private Map<Integer, Integer> getTriggerErrors(final String trigger, final int offset) {
        final Map<Integer, Integer> result = Maps.newTreeMap();
        final List<KeyValuePair> params = getParams(trigger, offset, result);

        // Check all parameters
        for (final KeyValuePair param : params) {
            boolean isBadValue = false;
            final String trimKey = param.getKey().trim(), trimValue = param.getValue().trim();
            if (trimKey.equals("Mode")) {
                if (!isTriggerApiLegal(trimValue)) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("Cost")) {
                if (!isCostLegal(trimValue)) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("Execute")) {
                if (sVars.contains(trimValue)) {
                    sVarAbilities.add(trimValue);
                } else {
                    isBadValue = true;
                }
            } else if (trimKey.equals("TriggerDescription")) {
                if (trimValue.isEmpty()) {
                    isBadValue = true;
                }
            } else if (trimKey.equals("ValidCard")) {
                if (!isValidLegal(trimValue) && !isDefinedLegal(trimValue)) {
                    isBadValue = true;
                }
            }
            // other keys are the trigger mode's own parameters: no registry, so no verdict
            if (isBadValue) {
                result.put(param.startIndexValue(), param.valueLength());
            }
        }
        return result;
    }

    /** The bracketed cost parts {@code forge.game.cost.Cost#parseCostPart} recognizes, without their {@code <}. */
    private static final Set<String> COST_PARTS = ImmutableSortedSet.of(
            "AddCounter", "AddCounterYou", "AddMana", "Behold", "BeholdExile", "Blight", "ChooseCard",
            "ChooseColor", "ChooseCreatureType", "CollectEvidence", "DamageYou", "Discard", "Draw",
            "Enlist", "Exert", "Exile", "ExileAnyGrave", "ExileCtrlOrGrave", "ExileFromGrave",
            "ExileFromHand", "ExileFromStack", "ExileFromTop", "ExileSameGrave", "ExiledMoveToGrave",
            "FlipCoin", "GainControl", "GainLife", "Mana", "Mill", "PayEnergy", "PayLife", "PayShards",
            "PutCardToLibFromBattlefield", "PutCardToLibFromGrave", "PutCardToLibFromHand",
            "PutCardToLibFromSameGrave", "RemoveAnyCounter", "Return", "Reveal", "RevealChosen",
            "RevealFromExile", "RevealOrChoose", "RollDice", "Sac", "SubCounter", "Teamwork", "Unattach",
            "Waterbend", "tapXType", "untapYType");
    /** The bare cost words the same parser recognizes. */
    private static final Set<String> COST_WORDS = ImmutableSortedSet.of(
            "T", "Tap", "Q", "Untap", "Mandatory", "Forage", "PromiseGift");

    /**
     * A cost as {@code forge.game.cost.Cost} reads it: space-separated parts (spaces inside {@code <...>}
     * belong to the part), each a bare cost word, an {@code XMin} marker, a bracketed cost part, or a
     * piece of the mana cost.
     */
    private static boolean isCostLegal(final String cost) {
        final String trimmed = cost.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (final String part : TextUtil.splitWithParenthesis(trimmed, ' ', '<', '>')) {
            if (COST_WORDS.contains(part) || part.startsWith("XMin") || isManaCostPart(part)) {
                continue;
            }
            final int open = part.indexOf('<');
            if (open > 0 && part.endsWith(">") && COST_PARTS.contains(part.substring(0, open))) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean isAbilityApiDeclarerLegal(final String declarer) {
        final String tDeclarer = declarer.trim();
        for (AbilityRecordType type : AbilityRecordType.values()) {
            if (type.getPrefix().equals(tDeclarer)) return true;
        }
        return false;
    }
    private static boolean isAbilityApiLegal(final String api) {
        try {
            return ApiType.smartValueOf(api.trim()) != null;
        } catch (final RuntimeException e) {
            return false;
        }
    }
    private static boolean isReplacementApiLegal(final String api) {
        try {
            return ReplacementType.smartValueOf(api.trim()) != null;
        } catch (final RuntimeException e) {
            return false;
        }
    }
    private static boolean isTriggerApiLegal(final String api) {
        try {
            return TriggerType.smartValueOf(api.trim()) != null;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    private static Predicate<String> startsWith(final String s) {
        return s::startsWith;
    }

    /**
     * The Defined words {@code AbilityUtils.getDefinedCards}, {@code getDefinedPlayers} and
     * {@code getDefinedSpellAbilities} match exactly, plus the paid-cost sets {@code getPaidCards} serves.
     */
    private static final Set<String> DEFINED_LITERAL = ImmutableSortedSet.of(
            "ActivePlayer", "AttackingPlayer", "CardController", "CardOwner", "Caster", "ChoosingPlayer",
            "ChosenCard", "ChosenPlayer", "Colors", "Convoked", "CorrectedSelf", "DefendingPlayer",
            "DelayTriggerRemembered", "DelayTriggerRememberedLKI", "DifferentColorPair", "DirectRemembered",
            "EffectSource", "Enchanted", "EnchantedPlayer", "Equipped", "Exiler", "Imprinted", "ImprintedLKI",
            "ManaSpender", "Opponent", "OriginalHost", "Parent", "ParentTarget", "ParentTargetedController",
            "Promised", "Registered", "Remembered", "RememberedCard", "RememberedFirst", "RememberedLKI",
            "RememberedLast", "Self", "SourceController", "SourceFirstSpell", "Targeted", "TargetedAndYou",
            "TargetedCard", "TargetedController", "TargetedOrController", "TargetedOwner", "TargetedPlayer",
            "TargetedSource", "ThisTargetedCard", "ThisTargetedController", "ThisTargetedOwner",
            "ThisTargetedPlayer", "TopOfGraveyard", "Triggered", "TriggeredAttacker", "TriggeredBlocker",
            "TriggeredCard", "TriggeredObject", "You",
            "TopOfLibrary", "BottomOfLibrary", "Clones", "SacrificedCards", "Sacrificed", "DiscardedCards",
            "Discarded", "ExiledCards", "Exiled", "TappedCards", "Tapped", "UntappedCards", "Untapped");
    /** The Defined prefixes the same methods match with {@code startsWith}. */
    private static final Set<String> DEFINED_PREFIX = ImmutableSortedSet.of(
            "AllTypes", "Amount", "AttachedBy", "AttachedTo", "CardTypes", "CardUID_", "ChosenCard",
            "CreatureType", "DelayTriggerRemembered", "Different", "EffectSource", "Enchanted", "Equipped",
            "ExiledWith", "Flipped", "Greatest", "Imprinted", "LandType", "Least", "NextOpponentToYour",
            "NextPlayerToYour", "Non", "OppNon", "OriginalHost", "PlayerNamed_", "PlayerUID_", "Remembered",
            "Replaced", "TapPowerValue", "Targeted", "This", "Top", "Triggered");
    /** The Defined suffixes {@code getDefinedPlayers} / {@code getDefinedSpellAbilities} match with {@code endsWith}. */
    private static final Set<String> DEFINED_SUFFIX = ImmutableSortedSet.of(
            "AndYou", "Controller", "OfLibrary", "Opponents", "Owner", "Remembered", "Targeted");

    /**
     * A Defined value: one of the words above, a {@code Valid...} card filter, or (as
     * {@code getDefinedPlayers} does with anything else) a player filter such as {@code Player.Opponent}.
     */
    private static boolean isDefinedLegal(final String defined) {
        if (defined.isEmpty()) {
            return false;
        }
        if (defined.startsWith("Valid")) {
            // "Valid <filter>", or "Valid<Zone> <filter>" (ValidGraveyard, ValidExile, ValidStack...)
            final String rest = defined.substring("Valid".length());
            final int space = rest.indexOf(' ');
            return isValidLegal(space < 0 ? rest : rest.substring(space + 1).trim());
        }
        final String head = defined.split("\\.", 2)[0];
        if (DEFINED_LITERAL.contains(head) || DEFINED_PREFIX.stream().anyMatch(startsWith(head))
                || DEFINED_SUFFIX.stream().anyMatch(head::endsWith)) {
            return true;
        }
        return isValidLegal(defined);
    }

    /** The entity words {@code Card.isValid} accepts in front of the first dot (a card type also qualifies). */
    private static final Set<String> VALID_CARD_INCLUSIVE = ImmutableSortedSet.of(
            "Spell", "Permanent", "Card", "card", "Any", "Effect", "Emblem", "Boon");
    /** The words {@code Player.isValid} accepts in front of the first dot. */
    private static final Set<String> VALID_PLAYER_INCLUSIVE = ImmutableSortedSet.of(
            "Player", "Opponent", "You", "Any");

    /**
     * A ValidTgts / ValidCards / ValidCard value: a comma-separated list ({@code TargetRestrictions}
     * splits on the comma) of {@code [!]Entity[.property+property...]}.
     */
    private static boolean isValidLegal(final String valid) {
        if (valid.isEmpty()) {
            return false;
        }
        for (final String part : StringUtils.split(valid, ',')) {
            if (!isSingleValidLegal(part.trim())) {
                return false;
            }
        }
        return true;
    }
    private static boolean isSingleValidLegal(final String valid) {
        String remaining = valid;
        if (remaining.startsWith("!")) {
            remaining = valid.substring(1);
        }
        if (remaining.isEmpty()) {
            return false;
        }
        final String[] splitDot = remaining.split("\\.", 2);
        final boolean card = VALID_CARD_INCLUSIVE.contains(splitDot[0]) || isSingleTypeLegal(splitDot[0]);
        final boolean player = VALID_PLAYER_INCLUSIVE.contains(splitDot[0]);
        if (!card && !player) {
            return false;
        }
        if (splitDot.length < 2) {
            return true;
        }

        final String[] splitPlus = StringUtils.split(splitDot[1], '+');
        for (final String excl : splitPlus) {
            if (!((card && isValidExclusive(excl)) || (player && isPlayerPropertyLegal(excl)))) {
                return false;
            }
        }
        return true;
    }

    /** The properties {@code PlayerProperty.playerHasProperty} matches exactly. */
    private static final Set<String> PLAYER_PROPERTY = ImmutableSortedSet.of(
            "Activator", "Active", "Allies", "Attacking", "BeenAttackedThisCombat", "CanBeEnchantedBy",
            "CardOwner", "CardsInHandAtBeginningOfTurn", "Chosen", "Defending", "EnchantedBy",
            "EnchantedController", "IsCorrupted", "IsPoisoned", "IsRemembered", "IsRememberedOrController",
            "IsTriggerRemembered", "LostLifeThisTurn", "MaxSpeed", "NoSpeed", "NonActive", "NotedDefender",
            "Opponent", "OpponentToActive", "OriginalHostRemembered", "Other", "TappedLandForManaThisTurn",
            "VenturedThisTurn", "You", "YourTeam", "attackedBySourceThisCombat", "attackedBySourceThisTurn",
            "attackedWithCreaturesThisTurn", "attackedYouTheirCurrentTurn", "attackedYouTheirLastTurn",
            "castSpellThisTurn", "committedCrimeThisTurn", "descended", "hasBlessing", "hasEnduringStory",
            "hasInitiative", "isMonarch", "targetedBy");
    /** The property prefixes the same method matches with {@code startsWith}. */
    private static final Set<String> PLAYER_PROPERTY_PREFIX = ImmutableSortedSet.of(
            "Condition", "HasCardsIn", "LostLifeThisTurn", "NotedFor", "OpponentOf", "PlayerUID_", "Triggered",
            "attackedYouCtrlTheirCurrentTurn", "controls", "counters", "damageDoneSingleSource", "hasFewer",
            "hasMore", "life", "wasAttackedThisTurnBy", "wasDealt", "withAtLeast", "withLowest", "withMore",
            "withMost");

    private static boolean isPlayerPropertyLegal(String property) {
        if (property.startsWith("!")) {
            property = property.substring(1);
        }
        return PLAYER_PROPERTY.contains(property) || PLAYER_PROPERTY_PREFIX.stream().anyMatch(startsWith(property));
    }

    /**
     * The card properties {@code CardProperty.cardHasProperty} and {@code CardStateProperty.hasProperty}
     * match exactly (their {@code equals} arms, 2026-09). What neither names falls through to the
     * card's types and colors, handled in {@link #isValidExclusive}.
     */
    private static final Set<String> CARD_PROPERTY = ImmutableSortedSet.of(
            "AdventureCard", "AssociatedWithChosenColor", "Attached", "BackSide", "CanBeSacrificedBy",
            "CanPayManaCost", "CanTransform", "CastSaSource", "ChosenSector", "ChosenType", "ChosenType2",
            "CostsPhyrexianMana", "CrewedBySourceThisTurn", "CrewedThisTurn", "Defending", "DifferentSector",
            "DiscardedThisTurn", "DoubleFaced", "EffectSource", "EnchantedBy", "EncodedWithSource",
            "EnteredSinceYourLastTurn", "ExiledWithEffectSource", "Flip", "FrontSide", "FullyUnlocked",
            "HasCounters", "HasDevoured", "Historic", "IsCommander", "IsGoaded", "IsImprinted", "IsMonstrous",
            "IsNotChosenType", "IsPrepared", "IsRemembered", "IsRenowned", "IsRingbearer", "IsSaddled",
            "IsSolved", "IsSuspected", "IsTriggerRemembered", "IsUnearthed", "NameNotEnchantingEnchantedPlayer",
            "NamedByRememberedPlayer", "NamedCard", "NoAbilities", "NotedColor", "NotedGuessPhantasm",
            "NotedNameAetherSearcher", "NotedNameNobleBanneret", "NotedNameSmugglerCaptain", "NotedType",
            "NotedTypes", "Outlaw", "Party", "Permanent", "PromisedGift", "SaddledThisTurn", "SharesCMCWith",
            "SharesColorWith", "Split", "TargetedPlayerCtrl", "TargetedPlayerOwn", "Teamwork", "ThisTurnCast",
            "ThisTurnEntered", "TopLibrary", "Transformed", "VisitedThisTurn", "Worthy",
            "attackedBySourceThisCombat", "attackedOrBlockedSinceYourLastUpkeep", "attackersBandedWith",
            "attacking", "attackingBattle", "attackingSame", "attackingYou", "bargained", "blitzed", "blocked",
            "blockedOrBeenBlockedSinceYourLastUpkeep", "blockedThisCombat", "canBeBeamedUp", "canBeTurnedFaceUp",
            "canProduceMana", "canProduceSameManaTypeWith", "castKeyword", "cloaked", "cmcChosenEvenOdd",
            "cmcEven", "cmcNotChosenEvenOdd", "cmcOdd", "couldAttackButNotAttacking", "dashed",
            "doesNotShareNameWith", "escaped", "evoked", "foretold", "hadToAttackThisCombat", "harnessed",
            "hasABasicLandType", "hasANonBasicLandType", "hasManaAbility", "hasNonManaActivatedAbility",
            "impended", "isDamaged", "kicked", "linkedCastSA", "manifested", "milledThisTurn", "noName",
            "nonChosenCard", "powerEven", "powerGTbasePower", "powerGTtoughness", "powerLTtoughness",
            "powerNOTbasePower", "powerOdd", "prowled", "sharesCardTypeWith", "sharesControllerWith",
            "sharesCreatureTypeWith", "sharesNameWith", "sharesOwnerWith", "sharesPermanentTypeWith", "sneaked",
            "spectacle", "surged", "surveilledThisTurn", "targetedBy", "warped", "wasDealtNonCombatDamageThisTurn",
            "webSlinged");
    /** The property prefixes the same two methods match with {@code startsWith}. */
    private static final Set<String> CARD_PROPERTY_PREFIX = ImmutableSortedSet.of(
            "Above", "ActivePlayerCtrl", "AllColors", "AnyChosenColor", "AttachedBy", "AttachedTo", "BorderColor",
            "Bottom", "BottomGraveyard", "BottomLibrary", "CanBeAttachedBy", "CanBeEnchantedBy", "CanBeTargetedBy",
            "CanEnchant", "CardUID_", "CastSa", "ChosenCard", "ChosenColor", "ChosenCtrl", "ChosenMode", "Cloned",
            "ControlledBy", "ControllerControls", "Damaged", "DamagedBy", "DefenderCtrl", "DefendingPlayer",
            "DirectlyAbove", "DrawnThisTurn", "Enchanted", "EnchantedBy", "EnchantedController", "EnchantedPlayer",
            "EnemyColor", "EnteredUnder", "EquippedBy", "ExiledByYou", "ExiledWithSource", "ExiledWithSourceLKI",
            "FortifiedBy", "FoughtThisTurn", "HasSVar", "HauntedBy", "ManaCost", "MonoColor", "MostProminentColor",
            "MostProminentCreatureTypeInLibrary", "MultiColor", "NotDefined", "NotedFor", "OppCtrl", "OppOwn",
            "OppProtect", "Other", "OwnedBy", "OwnerDoesntControl", "Paired", "ProtectedBy", "RememberedPlayer",
            "SecondSpellCastThisTurn", "Self", "SharesCMCWith", "SharesColorWith", "SharesColorWithOther",
            "StrictlyOther", "StrictlySelf", "ThisTurnEntered", "ThisTurnEnteredFrom", "TopGraveyard",
            "TopGraveyardCreature", "TopLibrary", "Triggered", "YouCtrl", "YouDontCtrl", "YouDontOwn", "YouOwn",
            "YourTeamCtrl", "activated", "attackedBattleThisTurn", "attackedLastTurn", "attackedThisCombat",
            "attackedThisTurn", "attackedYouThisTurn", "attacking", "attackingYouOrYourPW", "basePower",
            "baseToughness", "blockedByRemembered", "blockedBySource", "blockedBySourceLKI",
            "blockedBySourceThisTurn", "blockedByThisTurn", "blockedByValidThisTurn", "blockedRemembered",
            "blockedThisTurn", "blockedValidThisTurn", "blocking", "cameUnderControlSinceLastUpkeep",
            "canProduceManaColor", "canReceiveCounters", "cmc", "convoked", "copiedSpell", "counters",
            "dealtCombatDamageThisCombat", "dealtCombatDamageThisTurn", "dealtCombatDamagetoAny",
            "dealtDamageThisTurn", "dealtDamageToOppThisTurn", "dealtDamageToYouThisTurn", "dealtDamagetoAny",
            "delved", "doesNotShareNameWith", "enchanted", "enchanting", "enlistedThisCombat", "equalPT",
            "equipped", "equipping", "exploited", "faceDown", "faceUp", "firstTurnControlled", "gotBlockedThisTurn",
            "greatestCMC_", "greatestPower", "greatestRememberedCMC", "hasAbility", "hasKeyword", "hasXCost",
            "inRealZone", "inZone", "isBlockedByRemembered", "kicked", "leastPower", "leastToughness",
            "lowestCMC", "lowestRememberedCMC", "madness", "modified", "named", "notExertedThisTurn",
            "notTributed", "numColors", "numTypes", "phasedIn", "phasedOut", "power", "sameName", "set",
            "sharesAllCardTypesWithOther", "sharesBlockingAssignmentWith", "sharesCardTypeWith",
            "sharesCardTypeWithOther", "sharesControllerWith", "sharesCreatureTypeWith", "sharesLandTypeWith",
            "sharesNameWith", "sharesOwnerWith", "startedTheTurnUntapped", "suspended", "tapped", "token",
            "totalPT", "toughness", "turnedFaceUpThisTurn", "unblocked", "untapped", "wasCast", "wasCastFrom",
            "wasDealtDamageByThisGame", "wasDealtDamageThisTurn", "wasDealtExcessDamageThisTurn", "with", "without",
            "yardGreatestPower");
    /** The prefixes {@code CardProperty} follows with a two-letter comparator and an operand ({@code powerGE3}). */
    private static final Set<String> COMPARISON_PREFIX = ImmutableSortedSet.of(
            "basePower", "baseToughness", "cmc", "numColors", "numTypes", "power", "totalPT", "toughness");
    /** The comparator words {@code Expressions.compare} knows. */
    private static final Set<String> COMPARATORS = ImmutableSortedSet.of("EQ", "GE", "GT", "LE", "LT", "M2", "NE");

    /**
     * One {@code +}-separated card restriction: a named property, a color (with the {@code non} and
     * {@code Source} decorations {@code CardStateProperty} reads), or, as the game's last resort, a type.
     */
    private static boolean isValidExclusive(String valid) {
        if (valid.startsWith("!")) {
            valid = valid.substring(1);
        }
        if (valid.isEmpty()) {
            return false;
        }
        if (CARD_PROPERTY.contains(valid)) {
            return true;
        }
        for (final String prefix : COMPARISON_PREFIX) {
            if (valid.startsWith(prefix)) {
                // CardProperty slices the comparator at prefix + 2 and the operand after it, so "power" alone is a
                // StringIndexOutOfBoundsException the first time the filter runs and "powerful" a filter that never matches
                final String rest = valid.substring(prefix.length());
                return rest.length() > 2 && COMPARATORS.contains(rest.substring(0, 2));
            }
        }
        if (CARD_PROPERTY_PREFIX.stream().anyMatch(startsWith(valid))) {
            return true;
        }
        String plain = valid.startsWith("non") ? valid.substring("non".length()) : valid;
        if (plain.endsWith("Source") && plain.length() > "Source".length()) {
            plain = plain.substring(0, plain.length() - "Source".length());
        }
        if (plain.equals("Colorless") || MagicColor.fromName(plain) != 0) {
            return true;
        }
        return isSingleTypeLegal(plain);
    }

    private static final class KeyValuePair {
        private final String key, value;
        private final int index;

        private KeyValuePair(final String key, final String value, final int index) {
            this.key = key;
            this.value = value;
            this.index = index;
        }

        private String getKey() {
            return key;
        }
        private String getValue() {
            return value;
        }
        private int length() {
            return keyLength() + 1 + valueLength();
        }
        private int keyLength() {
            return key.length();
        }
        private int valueLength() {
            return value.length();
        }
        private int startIndex() {
            return index;
        }
        private int endIndexKey() {
            return startIndex() + key.length();
        }
        private int startIndexValue() {
            return endIndexKey() + 1;
        }
        private int endIndex() {
            return startIndex() + length();
        }
    }
}
