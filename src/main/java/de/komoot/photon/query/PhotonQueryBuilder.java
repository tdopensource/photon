package de.komoot.photon.query;


import com.google.common.collect.ImmutableSet;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Point;
import org.elasticsearch.common.lucene.search.function.CombineFunction;
import org.elasticsearch.common.lucene.search.function.FiltersFunctionScoreQuery.ScoreMode;
import org.elasticsearch.common.unit.Fuzziness;
import org.elasticsearch.index.query.*;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder;
import org.elasticsearch.index.query.functionscore.FunctionScoreQueryBuilder.FilterFunctionBuilder;
import org.elasticsearch.index.query.functionscore.ScoreFunctionBuilders;
import org.elasticsearch.index.query.functionscore.ScriptScoreFunctionBuilder;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptType;

import java.util.*;

import static com.google.common.collect.Maps.newHashMap;


/**
 * There are four {@link de.komoot.photon.query.PhotonQueryBuilder.State states} that this query builder goes through before a query can be executed on elastic search. Of
 * these, three are of importance.
 * <ul>
 * <li>{@link de.komoot.photon.query.PhotonQueryBuilder.State#PLAIN PLAIN} The query builder is being used to build a query without any tag filters.</li>
 * <li>{@link de.komoot.photon.query.PhotonQueryBuilder.State#FILTERED FILTERED} The query builder is being used to build a query that has tag filters and can no longer
 * be used to build a PLAIN filter.</li>
 * <li>{@link de.komoot.photon.query.PhotonQueryBuilder.State#FINISHED FINISHED} The query builder has been built and the query has been placed inside a
 * {@link QueryBuilder filtered query}. Further calls to any methods will have no effect on this query builder.</li>
 * </ul>
 * <p/>
 * Created by Sachin Dole on 2/12/2015.
 *
 * @see de.komoot.photon.query.TagFilterQueryBuilder
 */
public class PhotonQueryBuilder implements TagFilterQueryBuilder {
    private FunctionScoreQueryBuilder m_finalQueryWithoutTagFilterBuilder;

    private BoolQueryBuilder m_queryBuilderForTopLevelFilter;

    private State state;

    private BoolQueryBuilder orQueryBuilderForIncludeTagFiltering = null;

    private BoolQueryBuilder andQueryBuilderForExcludeTagFiltering = null;

    private MatchQueryBuilder defaultMatchQueryBuilder;

    private MatchQueryBuilder languageMatchQueryBuilder;

    private GeoBoundingBoxQueryBuilder bboxQueryBuilder;

    private BoolQueryBuilder m_finalQueryBuilder;

    protected ArrayList<FilterFunctionBuilder> m_alFilterFunction4QueryBuilder = new ArrayList<>(1);

    protected BoolQueryBuilder m_query4QueryBuilder;


    private PhotonQueryBuilder(String query, String language, QueryType queryType) {
        if (queryType.equals(QueryType.PLACES)) {
            defaultMatchQueryBuilder =
                    QueryBuilders.matchQuery("collector.default", query).fuzziness(Fuzziness.ZERO).prefixLength(2).analyzer("search_ngram").minimumShouldMatch("100%");

            languageMatchQueryBuilder = QueryBuilders.matchQuery(String.format("collector.%s.ngrams", language), query).fuzziness(Fuzziness.ZERO).prefixLength(2)
                    .analyzer("search_ngram").minimumShouldMatch("100%");

            // @formatter:off
            m_query4QueryBuilder = QueryBuilders.boolQuery()
                    .must(QueryBuilders.boolQuery().should(defaultMatchQueryBuilder).should(languageMatchQueryBuilder)
                            .minimumShouldMatch("1"));
            m_query4QueryBuilder = m_query4QueryBuilder
                    .should(QueryBuilders.matchQuery(String.format("name.%s.raw", language), query).boost(4).analyzer("search_raw"))
                    .should(QueryBuilders.matchQuery("name.default.raw", query).boost(3).analyzer("search_raw"))
                    .should(QueryBuilders.matchQuery(String.format("collector.%s.raw", language), query).boost(2).analyzer("search_raw"))
                    .should(QueryBuilders.matchQuery("collector.default.raw", query).boost(1).analyzer("search_raw"));
            // @formatter:on

            // this is former general-score, now inline
            String strCode = "double score = 1 + doc['importance'].value * 10; score";
            ScriptScoreFunctionBuilder functionBuilder4QueryBuilder =
                    ScoreFunctionBuilders.scriptFunction(new Script(ScriptType.INLINE, "painless", strCode, new HashMap<>()));

            m_alFilterFunction4QueryBuilder.add(new FilterFunctionBuilder(functionBuilder4QueryBuilder));
            m_finalQueryWithoutTagFilterBuilder = new FunctionScoreQueryBuilder(m_query4QueryBuilder, m_alFilterFunction4QueryBuilder.toArray(new FilterFunctionBuilder[0]))
                    .boostMode(CombineFunction.MULTIPLY).scoreMode(ScoreMode.MULTIPLY);

            // @formatter:off
            m_queryBuilderForTopLevelFilter = QueryBuilders.boolQuery()
                    .should(QueryBuilders.boolQuery().mustNot(QueryBuilders.existsQuery("housenumber")))
                    .should(QueryBuilders.matchQuery("housenumber", query).analyzer("standard"))
                    .should(QueryBuilders.existsQuery("name.default"));
            // @formatter:on
        } else if(queryType.equals(QueryType.ADDRESSES)) {
            defaultMatchQueryBuilder =
                    QueryBuilders.matchQuery("addresswithnumber.default.ngrams", query)
                            .operator(Operator.AND)
                            .fuzziness(Fuzziness.ZERO)
                            .prefixLength(2)
                            .analyzer("search_ngram")
                            .minimumShouldMatch("100%");

            languageMatchQueryBuilder = QueryBuilders.matchQuery(String.format("addresswithnumber.%s.ngrams", language), query)
                    .operator(Operator.AND)
                    .fuzziness(Fuzziness.ZERO)
                    .prefixLength(2)
                    .analyzer("search_ngram")
                    .minimumShouldMatch("100%");

            m_query4QueryBuilder = QueryBuilders.boolQuery()
                    .should(QueryBuilders.boolQuery().should(defaultMatchQueryBuilder).should(languageMatchQueryBuilder)
                            .minimumShouldMatch("1"));

            // this is former general-score, now inline
            String strCode = "double score = 1 + doc['importance'].value * 10; score";
            ScriptScoreFunctionBuilder functionBuilder4QueryBuilder =
                    ScoreFunctionBuilders.scriptFunction(new Script(ScriptType.INLINE, "painless", strCode, new HashMap<String, Object>()));

            m_alFilterFunction4QueryBuilder.add(new FilterFunctionBuilder(functionBuilder4QueryBuilder));
            m_finalQueryWithoutTagFilterBuilder = new FunctionScoreQueryBuilder(m_query4QueryBuilder, m_alFilterFunction4QueryBuilder.toArray(new FilterFunctionBuilder[0]))
                    .boostMode(CombineFunction.MULTIPLY).scoreMode(ScoreMode.MULTIPLY);

            // @formatter:off
            m_queryBuilderForTopLevelFilter = QueryBuilders.boolQuery()
                    .should(QueryBuilders.matchQuery("housenumber", query).analyzer("standard"))
                    .should(QueryBuilders.termQuery("object_type", "city"))
                    .should(QueryBuilders.boolQuery()
                                .must(QueryBuilders.termQuery( "osm_key", "highway"))
                                .must(QueryBuilders.existsQuery("name.default"))
                            )
                    .must(QueryBuilders.existsQuery("city.default"));
            // @formatter:on
        }


        state = State.PLAIN;
    }


    /**
     * Create an instance of this builder which can then be embellished as needed.
     *
     * @param query    the value for photon query parameter "q"
     * @param language
     * @param queryType
     * @return An initialized {@link TagFilterQueryBuilder photon query builder}.
     */
    public static TagFilterQueryBuilder builder(String query, String language, QueryType queryType) {
        return new PhotonQueryBuilder(query, language, queryType);
    }

    @Override
    public TagFilterQueryBuilder withLocationBias(Point point, double scale) {
        if (point == null) return this;
        Map<String, Object> params = newHashMap();
        params.put("lon", point.getX());
        params.put("lat", point.getY());

        scale = Math.abs(scale);
        String strCode = "double dist = doc['coordinate'].planeDistance(params.lat, params.lon); " +
                "double score = 0.1 + " + scale + " / (1.0 + dist * 0.001 / 10.0); " +
                "score";
        ScriptScoreFunctionBuilder builder = ScoreFunctionBuilders.scriptFunction(new Script(ScriptType.INLINE, "painless", strCode, params));
        m_alFilterFunction4QueryBuilder.add(new FilterFunctionBuilder(builder));
        m_finalQueryWithoutTagFilterBuilder =
                new FunctionScoreQueryBuilder(m_query4QueryBuilder, m_alFilterFunction4QueryBuilder.toArray(new FilterFunctionBuilder[0]))
                        .boostMode(CombineFunction.MULTIPLY);
        return this;
    }
    
     @Override
    public TagFilterQueryBuilder withBoundingBox(Envelope bbox) {
        if (bbox == null) return this;
        bboxQueryBuilder = new GeoBoundingBoxQueryBuilder("coordinate");
        bboxQueryBuilder.setCorners(bbox.getMaxY(), bbox.getMinX(), bbox.getMinY(), bbox.getMaxX());
        
        return this;
    }

    @Override
    public TagFilterQueryBuilder withTags(Map<String, Set<String>> tags) {
        if (!checkTags(tags)) return this;

        ensureFiltered();

        List<BoolQueryBuilder> termQueries = new ArrayList<BoolQueryBuilder>(tags.size());
        for (String tagKey : tags.keySet()) {
            Set<String> valuesToInclude = tags.get(tagKey);
            TermQueryBuilder keyQuery = QueryBuilders.termQuery("osm_key", tagKey);
            TermsQueryBuilder valueQuery = QueryBuilders.termsQuery("osm_value", valuesToInclude.toArray(new String[valuesToInclude.size()]));
            BoolQueryBuilder includeAndQuery = QueryBuilders.boolQuery().must(keyQuery).must(valueQuery);
            termQueries.add(includeAndQuery);
        }
        this.appendIncludeTermQueries(termQueries);
        return this;
    }


    @Override
    public TagFilterQueryBuilder withKeys(Set<String> keys) {
        if (!checkTags(keys)) return this;

        ensureFiltered();

        List<TermsQueryBuilder> termQueries = new ArrayList<TermsQueryBuilder>(keys.size());
        termQueries.add(QueryBuilders.termsQuery("osm_key", keys.toArray()));
        this.appendIncludeTermQueries(termQueries);
        return this;
    }


    @Override
    public TagFilterQueryBuilder withValues(Set<String> values) {
        if (!checkTags(values)) return this;

        ensureFiltered();

        List<TermsQueryBuilder> termQueries = new ArrayList<TermsQueryBuilder>(values.size());
        termQueries.add(QueryBuilders.termsQuery("osm_value", values.toArray(new String[values.size()])));
        this.appendIncludeTermQueries(termQueries);
        return this;
    }


    @Override
    public TagFilterQueryBuilder withTagsNotValues(Map<String, Set<String>> tags) {
        if (!checkTags(tags)) return this;

        ensureFiltered();

        List<BoolQueryBuilder> termQueries = new ArrayList<BoolQueryBuilder>(tags.size());
        for (String tagKey : tags.keySet()) {
            Set<String> valuesToInclude = tags.get(tagKey);
            TermQueryBuilder keyQuery = QueryBuilders.termQuery("osm_key", tagKey);
            TermsQueryBuilder valueQuery = QueryBuilders.termsQuery("osm_value", valuesToInclude.toArray(new String[valuesToInclude.size()]));

            BoolQueryBuilder includeAndQuery = QueryBuilders.boolQuery().must(keyQuery).mustNot(valueQuery);

            termQueries.add(includeAndQuery);
        }
        this.appendIncludeTermQueries(termQueries);
        return this;
    }


    @Override
    public TagFilterQueryBuilder withoutTags(Map<String, Set<String>> tagsToExclude) {
        if (!checkTags(tagsToExclude)) return this;

        ensureFiltered();

        List<QueryBuilder> termQueries = new ArrayList<>(tagsToExclude.size());
        for (String tagKey : tagsToExclude.keySet()) {
            Set<String> valuesToExclude = tagsToExclude.get(tagKey);
            TermQueryBuilder keyQuery = QueryBuilders.termQuery("osm_key", tagKey);
            TermsQueryBuilder valueQuery = QueryBuilders.termsQuery("osm_value", valuesToExclude.toArray(new String[valuesToExclude.size()]));

            BoolQueryBuilder withoutTagsQuery = QueryBuilders.boolQuery().mustNot(QueryBuilders.boolQuery().must(keyQuery).must(valueQuery));

            termQueries.add(withoutTagsQuery);
        }

        this.appendExcludeTermQueries(termQueries);

        return this;
    }


    @Override
    public TagFilterQueryBuilder withoutKeys(Set<String> keysToExclude) {
        if (!checkTags(keysToExclude)) return this;

        ensureFiltered();

        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery().mustNot(QueryBuilders.termsQuery("osm_key", keysToExclude.toArray()));

        LinkedList<QueryBuilder> lList = new LinkedList<>();
        lList.add(boolQuery);
        this.appendExcludeTermQueries(lList);

        return this;
    }


    @Override
    public TagFilterQueryBuilder withoutValues(Set<String> valuesToExclude) {
        if (!checkTags(valuesToExclude)) return this;

        ensureFiltered();

        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery().mustNot(QueryBuilders.termsQuery("osm_value", valuesToExclude.toArray()));

        LinkedList<QueryBuilder> lList = new LinkedList<>();
        lList.add(boolQuery);
        this.appendExcludeTermQueries(lList);

        return this;
    }


    @Override
    public TagFilterQueryBuilder withKeys(String... keys) {
        return this.withKeys(ImmutableSet.<String>builder().add(keys).build());
    }


    @Override
    public TagFilterQueryBuilder withValues(String... values) {
        return this.withValues(ImmutableSet.<String>builder().add(values).build());
    }


    @Override
    public TagFilterQueryBuilder withoutKeys(String... keysToExclude) {
        return this.withoutKeys(ImmutableSet.<String>builder().add(keysToExclude).build());
    }


    @Override
    public TagFilterQueryBuilder withoutValues(String... valuesToExclude) {
        return this.withoutValues(ImmutableSet.<String>builder().add(valuesToExclude).build());
    }


    @Override
    public TagFilterQueryBuilder withStrictMatch() {
        defaultMatchQueryBuilder.minimumShouldMatch("100%");
        languageMatchQueryBuilder.minimumShouldMatch("100%");
        return this;
    }


    @Override
    public TagFilterQueryBuilder withLenientMatch() {
        defaultMatchQueryBuilder.fuzziness(Fuzziness.ONE).minimumShouldMatch("-1");
        languageMatchQueryBuilder.fuzziness(Fuzziness.ONE).minimumShouldMatch("-1");
        return this;
    }


    /**
     * When this method is called, all filters are placed inside their {@link OrQueryBuilder OR} or {@link AndQueryBuilder AND} containers and the top level filter
     * builder is built. Subsequent invocations of this method have no additional effect. Note that after this method is called, calling other methods on this class also
     * have no effect.
     *
     * @see TagFilterQueryBuilder#buildQuery()
     */
    @Override
    public QueryBuilder buildQuery() {
        if (state.equals(State.FINISHED)) return m_finalQueryBuilder;

        m_finalQueryBuilder = QueryBuilders.boolQuery().must(m_finalQueryWithoutTagFilterBuilder).filter(m_queryBuilderForTopLevelFilter);

        if (state.equals(State.FILTERED)) {
            BoolQueryBuilder tagFilters = QueryBuilders.boolQuery();
            if (orQueryBuilderForIncludeTagFiltering != null)
                tagFilters.must(orQueryBuilderForIncludeTagFiltering);
            if (andQueryBuilderForExcludeTagFiltering != null)
                tagFilters.must(andQueryBuilderForExcludeTagFiltering);
            m_finalQueryBuilder.filter(tagFilters);
        }
        
        if (bboxQueryBuilder != null) 
            m_queryBuilderForTopLevelFilter.filter(bboxQueryBuilder);

        state = State.FINISHED;

        return m_finalQueryBuilder;
    }


    private Boolean checkTags(Set<String> keys) {
        return !(keys == null || keys.isEmpty());
    }


    private Boolean checkTags(Map<String, Set<String>> tags) {
        return !(tags == null || tags.isEmpty());
    }


    private void appendIncludeTermQueries(List<? extends QueryBuilder> termQueries) {

        if (orQueryBuilderForIncludeTagFiltering == null)
            orQueryBuilderForIncludeTagFiltering = QueryBuilders.boolQuery();

        for (QueryBuilder eachTagFilter : termQueries)
            orQueryBuilderForIncludeTagFiltering.should(eachTagFilter);
    }


    private void appendExcludeTermQueries(List<QueryBuilder> termQueries) {

        if (andQueryBuilderForExcludeTagFiltering == null)
            andQueryBuilderForExcludeTagFiltering = QueryBuilders.boolQuery();

        for (QueryBuilder eachTagFilter : termQueries)
            andQueryBuilderForExcludeTagFiltering.must(eachTagFilter);
    }


    private void ensureFiltered() {
        state = State.FILTERED;
    }


    private enum State {
        PLAIN, FILTERED, QUERY_ALREADY_BUILT, FINISHED,
    }
}
