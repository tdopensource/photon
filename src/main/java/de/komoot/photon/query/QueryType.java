package de.komoot.photon.query;

public enum QueryType {
    PLACES,
    ADDRESSES;

    public static QueryType getOrNull(String queryTypeStr) {
        for (QueryType queryType : QueryType.values()) {
            if (queryType.name().equalsIgnoreCase(queryTypeStr)) {
                return queryType;
            }
        }
        return null;
    }
}