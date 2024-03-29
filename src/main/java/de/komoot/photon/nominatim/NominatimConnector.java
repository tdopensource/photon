package de.komoot.photon.nominatim;

import com.google.common.collect.ImmutableList;
import com.vividsolutions.jts.geom.Envelope;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.GeometryFactory;
import com.vividsolutions.jts.geom.Point;
import com.vividsolutions.jts.linearref.LengthIndexedLine;
import de.komoot.photon.Importer;
import de.komoot.photon.PhotonDoc;
import de.komoot.photon.nominatim.model.AddressRow;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.dbcp2.BasicDataSource;
import org.postgis.jts.JtsWrapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A Nominatim result consisting of the basic PhotonDoc for the object
 * and a map of attached house numbers together with their respective positions.
 */
class NominatimResult {
    private PhotonDoc doc;
    private Map<String, Point> housenumbers;

    public NominatimResult(PhotonDoc baseobj) {
        doc = baseobj;
        housenumbers = null;
    }

    PhotonDoc getBaseDoc() {
        return doc;
    }

    boolean isUsefulForIndex() {
        return (housenumbers != null && !housenumbers.isEmpty()) || doc.isUsefulForIndex();
    }

    List<PhotonDoc> getDocsWithHousenumber() {
        if (housenumbers == null || housenumbers.isEmpty()) {
            return ImmutableList.of(doc);
        }

        List<PhotonDoc> results = new ArrayList<PhotonDoc>(housenumbers.size());
        for (Map.Entry<String, Point> e : housenumbers.entrySet()) {
            PhotonDoc copy = new PhotonDoc(doc);
            copy.setHouseNumber(e.getKey());
            copy.setCentroid(e.getValue());
            results.add(copy);
        }

        return results;
    }

    /**
     * Adds house numbers from a house number string.
     * <p>
     * This may either be a single house number or multiple
     * house numbers delimited by a semicolon. All locations
     * will be set to the centroid of the doc geometry.
     *
     * @param str House number string. May be null, in which case nothing is added.
     */
    public void addHousenumbersFromString(String str) {
        if (str == null || str.isEmpty())
            return;

        if (housenumbers == null)
            housenumbers = new HashMap<String, Point>();

        String[] parts = str.split(";");
        for (String part : parts) {
            String h = part.trim();
            if (!h.isEmpty())
                housenumbers.put(h, doc.getCentroid());
        }
    }

    public void addHouseNumbersFromInterpolation(long first, long last, String interpoltype, Geometry geom) {
        if (last <= first || (last - first) > 1000)
            return;

        if (housenumbers == null)
            housenumbers = new HashMap<String, Point>();

        LengthIndexedLine line = new LengthIndexedLine(geom);
        double si = line.getStartIndex();
        double ei = line.getEndIndex();
        double lstep = (ei - si) / (double) (last - first);

        // leave out first and last, they have a distinct OSM node that is already indexed
        long step = 2;
        long num = 1;
        if (interpoltype.equals("odd")) {
            if (first % 2 == 1)
                ++num;
        } else if (interpoltype.equals("even")) {
            if (first % 2 == 0)
                ++num;
        } else {
            step = 1;
        }

        GeometryFactory fac = geom.getFactory();
        for (; first + num < last; num += step) {
            housenumbers.put(String.valueOf(num + first), fac.createPoint(line.extractPoint(si + lstep * num)));
        }
    }
}

/**
 * Export nominatim data
 *
 * @author felix, christoph
 */
@Slf4j
public class NominatimConnector {
    private final JdbcTemplate template;
    private Map<String, Map<String, String>> countryNames;
    /**
     * Maps a row from location_property_osmline (address interpolation lines) to a photon doc.
     */
    private final RowMapper<NominatimResult> osmlineRowMapper = new RowMapper<NominatimResult>() {
        @Override
        public NominatimResult mapRow(ResultSet rs, int rownum) throws SQLException {
            Geometry geometry = DBUtils.extractGeometry(rs, "linegeo");

            PhotonDoc doc = new PhotonDoc(
                    rs.getLong("place_id"),
                    "W",
                    rs.getLong("osm_id"),
                    "place",
                    "house_number",
                    Collections.<String, String>emptyMap(), // no name
                    (String) null,
                    Collections.<String, String>emptyMap(), // no address
                    Collections.<String, String>emptyMap(), // no extratags
                    (Envelope) null,
                    rs.getLong("parent_place_id"),
                    0d, // importance
                    rs.getString("country_code"),
                    (Point) null, // centroid
                    0,
                    30
            );
            doc.setPostcode(rs.getString("postcode"));
            doc.setCountry(getCountryNames(rs.getString("country_code")));

            NominatimResult result = new NominatimResult(doc);
            result.addHouseNumbersFromInterpolation(rs.getLong("startnumber"), rs.getLong("endnumber"), rs.getString("interpolationtype"), geometry);

            return result;
        }
    };
    /**
     * maps a placex row in nominatim to a photon doc, some attributes are still missing and can be derived by connected address items.
     */
    private final RowMapper<NominatimResult> placeRowMapper = new RowMapper<NominatimResult>() {
        @Override
        public NominatimResult mapRow(ResultSet rs, int rowNum) throws SQLException {

            Double importance = rs.getDouble("importance");
            if (rs.wasNull()) {
                // https://github.com/komoot/photon/issues/12
                int rankSearch = rs.getInt("rank_search");
                importance = 0.75 - rankSearch / 40d;
            }

            Geometry geometry = DBUtils.extractGeometry(rs, "bbox");
            Envelope envelope = geometry != null ? geometry.getEnvelopeInternal() : null;

            PhotonDoc doc = new PhotonDoc(
                    rs.getLong("place_id"),
                    rs.getString("osm_type"),
                    rs.getLong("osm_id"),
                    rs.getString("class"),
                    rs.getString("type"),
                    DBUtils.getMap(rs, "name"),
                    (String) null,
                    DBUtils.getMap(rs, "address"),
                    DBUtils.getMap(rs, "extratags"),
                    envelope,
                    rs.getLong("parent_place_id"),
                    importance,
                    rs.getString("country_code"),
                    (Point) DBUtils.extractGeometry(rs, "centroid"),
                    rs.getLong("linked_place_id"),
                    rs.getInt("rank_address")
            );

            doc.setPostcode(rs.getString("postcode"));
            doc.setCountry(getCountryNames(rs.getString("country_code")));

            NominatimResult result = new NominatimResult(doc);
            result.addHousenumbersFromString(rs.getString("housenumber"));

            return result;
        }
    };
    private final String selectColsPlaceX = "place_id, osm_type, osm_id, class, type, name, housenumber, postcode, address, extratags, ST_Envelope(geometry) AS bbox, parent_place_id, linked_place_id, rank_address, rank_search, importance, country_code, centroid";
    private final String selectColsOsmline = "place_id, osm_id, parent_place_id, startnumber, endnumber, interpolationtype, postcode, country_code, linegeo";
    private final String selectColsAddress = "p.place_id, p.name, p.class, p.type, p.rank_address";
    private Importer importer;

    private Map<String, String> getCountryNames(String countrycode) {
        if (countryNames == null) {
            countryNames = new HashMap<String, Map<String, String>>();
            template.query("SELECT country_code, name FROM country_name;", new RowCallbackHandler() {
                        @Override
                        public void processRow(ResultSet rs) throws SQLException {
                            countryNames.put(rs.getString("country_code"), DBUtils.getMap(rs, "name"));
                        }
                    }
            );
        }

        return countryNames.get(countrycode);
    }

    /**
     * @param host     database host
     * @param port     database port
     * @param database database name
     * @param username db username
     * @param password db username's password
     */
    public NominatimConnector(String host, int port, String database, String username, String password) {
        BasicDataSource dataSource = buildDataSource(host, port, database, username, password, false);

        template = new JdbcTemplate(dataSource);
        template.setFetchSize(10000);
    }

    static BasicDataSource buildDataSource(String host, int port, String database, String username, String password, boolean autocommit) {
        BasicDataSource dataSource = new BasicDataSource();

        dataSource.setUrl(String.format("jdbc:postgres_jts://%s:%d/%s", host, port, database));
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setDriverClassName(JtsWrapper.class.getCanonicalName());
        dataSource.setDefaultAutoCommit(autocommit);
        return dataSource;
    }

    public void setImporter(Importer importer) {
        this.importer = importer;
    }

    public List<PhotonDoc> getByPlaceId(long placeId) {
        NominatimResult result = template.queryForObject("SELECT " + selectColsPlaceX + " FROM placex WHERE place_id = ?", new Object[] { placeId }, placeRowMapper);
        completePlace(result.getBaseDoc());
        return result.getDocsWithHousenumber();
    }

    public List<PhotonDoc> getInterpolationsByPlaceId(long placeId) {
        NominatimResult result = template.queryForObject("SELECT " + selectColsOsmline + " FROM location_property_osmline WHERE place_id = ?", new Object[] { placeId }, osmlineRowMapper);
        completePlace(result.getBaseDoc());
        return result.getDocsWithHousenumber();
    }

    List<AddressRow> getAddresses(PhotonDoc doc) {
        RowMapper<AddressRow> rowMapper = new RowMapper<AddressRow>() {
            @Override
            public AddressRow mapRow(ResultSet rs, int rowNum) throws SQLException {
                return new AddressRow(
                        rs.getLong("place_id"),
                        DBUtils.getMap(rs, "name"),
                        rs.getString("class"),
                        rs.getString("type"),
                        rs.getInt("rank_address")
                );
            }
        };

        boolean isPoi = doc.getRankAddress() > 28;
        long placeId = (isPoi) ? doc.getParentPlaceId() : doc.getPlaceId();

        List<AddressRow> terms = template.query("SELECT " + selectColsAddress + " FROM placex p, place_addressline pa WHERE p.place_id = pa.address_place_id and pa.place_id = ? and pa.cached_rank_address > 4 and pa.address_place_id != ? and pa.isaddress order by rank_address desc,fromarea desc,distance asc,rank_search desc", new Object[]{placeId, placeId}, rowMapper);

        if (isPoi) {
            // need to add the term for the parent place ID itself
            terms.addAll(0, template.query("SELECT " + selectColsAddress + " FROM placex p WHERE p.place_id = ?", new Object[]{placeId}, rowMapper));
        } else {
            // we have to add information from current doc to have full address information (entry presented with range 0, eg. - https://nominatim.openstreetmap.org/ui/details.html?osmtype=W&osmid=334911186&class=highway)
            terms.add(0, doc.asAddress());
        }

        return terms;
    }

    private static final PhotonDoc FINAL_DOCUMENT = new PhotonDoc(0, null, 0, null, null, null, null, null, null, null, 0, 0, null, null, 0, 0);

    private class ImportThread implements Runnable {
        private final BlockingQueue<PhotonDoc> documents;

        public ImportThread(BlockingQueue<PhotonDoc> documents) {
            this.documents = documents;
        }

        @Override
        public void run() {
            while (true) {
                PhotonDoc doc;
                try {
                    doc = documents.take();
                    if (doc == FINAL_DOCUMENT)
                        break;
                    importer.add(doc);
                } catch (InterruptedException e) {
                    log.info("interrupted exception ", e);
                }
            }
            importer.finish();
        }
    }

    static String convertCountryCode(String... countryCodes) {
        String countryCodeStr = "";
        for (String cc : countryCodes) {
            // "".split(",") results in 'new String[]{""}' and not 'new String[0]'
            if (cc.isEmpty())
                continue;
            if (cc.length() != 2)
                throw new IllegalArgumentException("country code invalid " + cc);
            if (!countryCodeStr.isEmpty())
                countryCodeStr += ",";
            countryCodeStr += "'" + cc.toLowerCase() + "'";
        }
        return countryCodeStr;
    }

    /**
     * parses every relevant row in placex, creates a corresponding document and calls the {@link #importer} for every document
     */
    public void readEntireDatabase(String... countryCodes) {
        final int progressInterval = 50000;
        final long startMillis = System.currentTimeMillis();

        String andCountryCodeStr = "", whereCountryCodeStr = "";
        String countryCodeStr = convertCountryCode(countryCodes);
        if (!countryCodeStr.isEmpty()) {
            andCountryCodeStr = "AND country_code in (" + countryCodeStr + ")";
            whereCountryCodeStr = "WHERE country_code in (" + countryCodeStr + ")";
        }

        log.info("start importing documents from nominatim (" + (countryCodeStr.isEmpty() ? "global" : countryCodeStr) + ")");

        final BlockingQueue<PhotonDoc> documents = new LinkedBlockingDeque<>(20);
        Thread importThread = new Thread(new ImportThread(documents));
        importThread.start();
        final AtomicLong counter = new AtomicLong();
        template.query("SELECT " + selectColsPlaceX +
                " FROM placex " +
                " WHERE linked_place_id IS NULL AND centroid IS NOT NULL " + andCountryCodeStr +
                " ORDER BY geometry_sector; ", new RowCallbackHandler() {
            @Override
            public void processRow(ResultSet rs) throws SQLException {
                // turns a placex row into a photon document that gathers all de-normalised information

                NominatimResult docs = placeRowMapper.mapRow(rs, 0);

                if (!docs.isUsefulForIndex()) return; // do not import document

                // finalize document by taking into account the higher level placex rows assigned to this row
                completePlace(docs.getBaseDoc());

                for (PhotonDoc doc : docs.getDocsWithHousenumber()) {
                    while (true) {
                        try {
                            documents.put(doc);
                        } catch (InterruptedException e) {
                            log.warn("Thread interrupted while placing document in queue.");
                            continue;
                        }
                        break;
                    }
                    if (counter.incrementAndGet() % progressInterval == 0) {
                        final double documentsPerSecond = 1000d * counter.longValue() / (System.currentTimeMillis() - startMillis);
                        log.info(String.format("imported %s documents [%.1f/second]", MessageFormat.format("{0}", counter.longValue()), documentsPerSecond));
                    }
                }
            }
        });

        template.query("SELECT place_id, osm_id, parent_place_id, startnumber, endnumber, interpolationtype, postcode, country_code, linegeo " +
                " FROM location_property_osmline " +
                whereCountryCodeStr +
                " ORDER BY geometry_sector; ", new RowCallbackHandler() {
            @Override
            public void processRow(ResultSet rs) throws SQLException {
                NominatimResult docs = osmlineRowMapper.mapRow(rs, 0);

                if (!docs.isUsefulForIndex()) return; // do not import document

                // finalize document by taking into account the higher level placex rows assigned to this row
                completePlace(docs.getBaseDoc());

                for (PhotonDoc doc : docs.getDocsWithHousenumber()) {
                    while (true) {
                        try {
                            documents.put(doc);
                        } catch (InterruptedException e) {
                            log.warn("Thread interrupted while placing document in queue.");
                            continue;
                        }
                        break;
                    }
                    if (counter.incrementAndGet() % progressInterval == 0) {
                        final double documentsPerSecond = 1000d * counter.longValue() / (System.currentTimeMillis() - startMillis);
                        log.info(String.format("imported %s documents [%.1f/second]", MessageFormat.format("{0}", counter.longValue()), documentsPerSecond));
                    }
                }
            }
        });

        while (true) {
            try {
                documents.put(FINAL_DOCUMENT);
                importThread.join();
            } catch (InterruptedException e) {
                log.warn("Thread interrupted while placing document in queue.");
                continue;
            }
            break;
        }
        log.info(String.format("finished import of %s photon documents.", MessageFormat.format("{0}", counter.longValue())));
    }

    /**
     * retrieves a single document, used for testing / developing
     *
     * @param osmId
     * @param osmType 'N': node, 'W': way or 'R' relation
     * @return
     */
    public List<PhotonDoc> readDocument(long osmId, char osmType) {
        return template.query("SELECT " + selectColsPlaceX + " FROM placex WHERE osm_id = ? AND osm_type = ?; ", new Object[]{osmId, osmType}, new RowMapper<PhotonDoc>() {
            @Override
            public PhotonDoc mapRow(ResultSet resultSet, int i) throws SQLException {
                PhotonDoc doc = placeRowMapper.mapRow(resultSet, 0).getBaseDoc();
                completePlace(doc);
                return doc;
            }
        });
    }

    /**
     * querying nominatim's address hierarchy to complete photon doc with missing data (like country, city, street, ...)
     *
     * @param doc
     */
    private void completePlace(PhotonDoc doc) {
        final List<AddressRow> addresses = getAddresses(doc);
        int cityNameAddressRank = -1;
        for (AddressRow address : addresses) {
            if (address.isCity()) {
                // if we have village like Białoboki we have to take address name with biggest rank
                // example of village - https://nominatim.openstreetmap.org/ui/details.html?osmtype=R&osmid=6778130&class=boundary - before this change we got "gmina Gać" as a result
                if (address.getRankAddress() > cityNameAddressRank) {
                    cityNameAddressRank = address.getRankAddress();
                    if (doc.getCity() == null) {
                        doc.setCity(address.getName());
                    } else {
                        doc.getContext().add(address.getName());
                    }
                } else {
                    log.debug("City name, used " + doc.getCity() + " instead of " + address.getName());
                }
                continue;
            }

            if (address.isStreet() && doc.getStreet() == null) {
                doc.setStreet(address.getName());
                continue;
            }

            if (address.isLocality() && doc.getLocality() == null) {
                doc.setLocality(address.getName());
                continue;
            }

            if (address.isDistrict() && doc.getDistrict() == null) {
                doc.setDistrict(address.getName());
                continue;
            }

            if (address.isCounty() && doc.getCounty() == null) {
                doc.setCounty(address.getName());
                continue;
            }

            if (address.isState() && doc.getState() == null) {
                doc.setState(address.getName());
                continue;
            }

            // no specifically handled item, check if useful for context
            if (address.isUsefulForContext()) {
                doc.getContext().add(address.getName());
            }
        }
        // finally, overwrite gathered information with higher prio
        // address info from nominatim which should have precedence
        doc.completeFromAddress();
    }
}
