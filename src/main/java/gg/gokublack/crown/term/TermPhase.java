package gg.gokublack.crown.term;

/** The three states of the term machine (spec 4.1). */
public enum TermPhase {
    /** A monarch holds the term. */
    REIGN,
    /** Voting is open. The sitting monarch keeps the role but their powers are frozen. */
    ELECTION,
    /** No monarch: a failed election, a resignation or a removal. */
    INTERREGNUM;

    public static TermPhase byName(String name) {
        for (TermPhase p : values()) {
            if (p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return INTERREGNUM;
    }
}
