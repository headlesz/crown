package gg.gokublack.crown.term;

/** Why {@link TermManager#transition} was called. Recorded in the ledger and the JSON export. */
public enum TransitionCause {
    /** The term ran its length and the election tallied cleanly. */
    TERM_ELAPSED,
    /** The monarch abdicated via {@code /crown resign}. */
    RESIGNATION,
    /** An operator removed the monarch. */
    ADMIN_REMOVAL,
    /** An election closed without a usable result and extensions were exhausted. */
    FAILED_ELECTION,
    /** An election concluded while the throne was empty. */
    INTERREGNUM_RESOLVED,
    /** First term of the campaign. */
    CAMPAIGN_START
}
