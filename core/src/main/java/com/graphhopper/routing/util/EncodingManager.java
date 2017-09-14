/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for 
 *  additional information regarding copyright ownership.
 * 
 *  GraphHopper GmbH licenses this file to you under the Apache License, 
 *  Version 2.0 (the "License"); you may not use this file except in 
 *  compliance with the License. You may obtain a copy of the License at
 * 
 *       http://www.apache.org/licenses/LICENSE-2.0
 * 
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.graphhopper.routing.util;

import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderRelation;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.routing.profiles.*;
import com.graphhopper.routing.weighting.TurnWeighting;
import com.graphhopper.storage.Directory;
import com.graphhopper.storage.IntsRef;
import com.graphhopper.storage.RAMDirectory;
import com.graphhopper.storage.StorableProperties;
import com.graphhopper.util.EdgeIteratorState;
import com.graphhopper.util.PMap;

import java.util.*;

/**
 * Manager class to register encoder, assign their flag values and check objects with all encoders
 * during parsing.
 *
 * @author Peter Karich
 * @author Nop
 */
public class EncodingManager implements EncodedValueLookup {

    private final Map<String, TagParser> parsers = new HashMap<>();
    private final Collection<ReaderWayFilter> filters = new HashSet<>();
    private final TagsParser parser;
    private final int extendedDataSize;

    // backward compatibility variables:
    /**
     * for backward compatibility we have to specify an encoder name ("vehicle")
     */
    public static final String ENCODER_NAME = "weighting";
    private static final String ERR = "Encoders are requesting %s bits, more than %s bits of %s flags. ";
    private static final String WAY_ERR = "Decrease the number of vehicles or increase the flags to take long via graph.bytes_for_flags=8";
    private final List<AbstractFlagEncoder> edgeEncoders = new ArrayList<AbstractFlagEncoder>();
    private int bitsForEdgeFlags;
    private final int bitsForTurnFlags = 8 * 4;
    private boolean enableInstructions = true;
    private String preferredLanguage = "";

    /**
     * This constructor creates the object that orchestrates the edge properties.
     *
     * @param extendedDataSize in bytes
     */
    private EncodingManager(TagsParser parser, int extendedDataSize) {
        this.parser = parser;
        this.extendedDataSize = Math.max(1, extendedDataSize / 4) * 4;
    }

    public IntsRef createIntsRef() {
        return new IntsRef(extendedDataSize / 4);
    }

    public static class Builder {
        private boolean buildCalled;
        private final EncodingManager em;
        private int nextNodeBit = 0;
        private int nextRelBit = 0;
        private int nextTurnBit = 0;

        /**
         * used only for tests, could be removed in 0.11
         */
        public Builder() {
            this(new TagsParser() {
                @Override
                public void parse(Collection<TagParser> parsers, IntsRef ints, ReaderWay way) {
                }
            }, 8);
        }

        public Builder(TagsParser parser, int extendedDataSize) {
            this.em = new EncodingManager(parser, extendedDataSize);
        }

        /**
         * This method adds some EncodedValues that a few FlagEncoders require like roundabout and road_class
         */
        public Builder addGlobalEncodedValues() {
            add(TagParserFactory.createRoundabout(new BooleanEncodedValue(TagParserFactory.ROUNDABOUT, false)));
            List<String> roadClasses = Arrays.asList("_default", "footway", "path", "steps", "pedestrian", "living_street", "track",
                    "residential", "service", "trunk", "trunk_link", "motorway", "motorway_link", "motorroad",
                    "primary", "primary_link", "secondary", "secondary_link", "tertiary", "tertiary_link",
                    "cycleway", "unclassified", "road", "bridleway");
            add(TagParserFactory.createRoadClass(new StringEncodedValue(TagParserFactory.ROAD_CLASS, roadClasses, "_default")));

            List<String> roadEnvList = Arrays.asList("_default", "bridge", "tunnel", "ford", "aerialway");
            add(TagParserFactory.createRoadEnvironment(new StringEncodedValue(TagParserFactory.ROAD_ENVIRONMENT, roadEnvList, "_default"), roadEnvList));
            return this;
        }

        public Builder add(TagParser parser) {
            check();
            TagParser old = em.parsers.get(parser.getName());
            if (old != null)
                throw new IllegalArgumentException("Cannot add parser " + old.getName() + ". Already existing: " + parser.getName());

            // TODO NOW: throw exception if byte limit exceeded! similar to WAY_ERR
            em.parsers.put(parser.getName(), parser);
            em.filters.add(parser.getReadWayFilter());
            return this;
        }

        public Builder addAllFlagEncoders(String encoderList) {
            addAll(FlagEncoderFactory.DEFAULT, encoderList, 4);
            return this;
        }

        /**
         * Use TagParsers instead of FlagEncoder
         */
        public Builder addAll(FlagEncoderFactory factory, String encoderList, int bytesForFlags) {
            addAll(parseEncoderString(factory, encoderList), bytesForFlags);
            return this;
        }

        /**
         * Use TagParsers instead of FlagEncoder
         */
        public Builder addAll(FlagEncoder... encoders) {
            addAll(Arrays.asList(encoders), 4);
            return this;
        }

        /**
         * Use TagParsers instead of FlagEncoder
         */
        public Builder addAll(List<? extends FlagEncoder> list, int bytesForFlags) {
            em.bitsForEdgeFlags = bytesForFlags * 8;
            for (FlagEncoder flagEncoder : list) {
                add(flagEncoder);
            }
            return this;
        }

        private Builder add(FlagEncoder flagEncoder) {
            check();

            AbstractFlagEncoder encoder = (AbstractFlagEncoder) flagEncoder;
            for (FlagEncoder fe : em.edgeEncoders) {
                if (fe.toString().equals(encoder.toString()))
                    throw new IllegalArgumentException("Cannot register edge encoder. Name already exists: " + fe.toString());
            }
            if (encoder.isRegistered())
                throw new IllegalStateException("You must not register the FlagEncoder '" + encoder.toString() + "' twice!");
            encoder.setEncodedValueLookup(em);
            em.edgeEncoders.add(encoder);

            for (Map.Entry<String, TagParser> entry : encoder.createTagParsers(getPrefix(encoder)).entrySet()) {
                if (entry.getValue() == null) {
                    if (!em.parsers.containsKey(entry.getKey()))
                        throw new IllegalArgumentException("FlagEncoder " + encoder.toString() + " requires the TagParser '" + entry.getKey() + "' and this must be created before adding the FlagEncoder");

                } else {
                    add(entry.getValue());
                }
            }

            return this;
        }

        public EncodingManager build() {
            check();

            EncodedValue.InitializerConfig initializer = new EncodedValue.InitializerConfig();
            for (TagParser tp : em.parsers.values()) {
                tp.getEncodedValue().init(initializer);
            }

            if (em.edgeEncoders.isEmpty()) {
                // we have to add a fake encoder that uses 0 bits of the old flags for backward compatibility with EncodingManager
                add(new CarFlagEncoder(0, 1, 0) {

                    @Override
                    public Access getAccess(ReaderWay way) {
                        // if one way is needed from one EncodedValue then it parsed the way
                        for (ReaderWayFilter filter : em.filters) {
                            if (filter.accept(way))
                                return Access.WAY;
                        }
                        return Access.CAN_SKIP;
                    }

                    @Override
                    public IntsRef handleWayTags(IntsRef ints, ReaderWay way, Access access, long relationFlags) {
                        // for backward compatibility return flags=1111...
                        for (int i = 0; i < ints.length; i++)
                            ints.ints[i] = ~0;
                        return ints;
                    }

                    @Override
                    public void applyWayTags(ReaderWay way, EdgeIteratorState edge) {
                        // do nothing
                    }

                    /**
                     * Analyze tags on osm node. Store node tags (barriers etc) for later usage while parsing way.
                     */
                    @Override
                    public long handleNodeTags(ReaderNode node) {
                        // TODO not implemented
                        return 0;
                    }

                    @Override
                    public long handleRelationTags(ReaderRelation relation, long oldRelationFlags) {
                        // TODO
                        return oldRelationFlags;
                    }

                    @Override
                    public boolean supports(Class<?> feature) {
                        return super.supports(feature);
                    }

                    @Override
                    public String toString() {
                        return ENCODER_NAME;
                    }
                });

                // TODO it should not fail when using 0 bits, if 0 it seems that the storage access is wrong or overwrites the bit space reserved for the waygeometry.
                em.bitsForEdgeFlags = 4 * 8;

            } else {

                for (FlagEncoder flagEncoder : em.edgeEncoders) {
                    AbstractFlagEncoder encoder = (AbstractFlagEncoder) flagEncoder;

                    int currentEncoderIndex = em.edgeEncoders.size();
                    int usedBits = encoder.defineNodeBits(currentEncoderIndex, nextNodeBit);
                    if (usedBits > em.bitsForEdgeFlags)
                        throw new IllegalArgumentException(String.format(ERR, usedBits, em.bitsForEdgeFlags, "node"));
                    encoder.setNodeBitMask(usedBits - nextNodeBit, nextNodeBit);
                    nextNodeBit = usedBits;

                    encoder.initEncodedValues(getPrefix(encoder), currentEncoderIndex);
                    encoder.setRegistered(true);
                    usedBits = encoder.defineRelationBits(currentEncoderIndex, nextRelBit);
                    if (usedBits > em.bitsForEdgeFlags)
                        throw new IllegalArgumentException(String.format(ERR, usedBits, em.bitsForEdgeFlags, "relation"));
                    encoder.setRelBitMask(usedBits - nextRelBit, nextRelBit);
                    nextRelBit = usedBits;

                    // turn flag bits are independent from edge encoder bits
                    usedBits = encoder.defineTurnBits(currentEncoderIndex, nextTurnBit);
                    if (usedBits > em.bitsForTurnFlags)
                        throw new IllegalArgumentException(String.format(ERR, usedBits, em.bitsForTurnFlags, "turn"));
                    nextTurnBit = usedBits;

                    if (em.bitsForEdgeFlags == 0)
                        throw new IllegalStateException("bytes_for_flags was not specified?");
                }
            }

            buildCalled = true;
            return em;
        }

        static String getPrefix(FlagEncoder encoder) {
            return encoder.toString() + ".";
        }

        private void check() {
            if (buildCalled)
                throw new IllegalStateException("EncodingManager.Builder.build() already called");
        }
    }

    // TODO move later into builder
    public void setEnableInstructions(boolean enableInstructions) {
        this.enableInstructions = enableInstructions;
    }

    public void setPreferredLanguage(String preferredLanguage) {
        if (preferredLanguage == null)
            throw new IllegalArgumentException("preferred language cannot be null");

        this.preferredLanguage = preferredLanguage;
    }

    /**
     * Size of arbitrary storage per edge and in bytes
     */
    public int getExtendedDataSize() {
        return extendedDataSize;
    }

    @Override
    public BooleanEncodedValue getBooleanEncodedValue(String key) {
        return getEncodedValue(key, BooleanEncodedValue.class);
    }

    @Override
    public IntEncodedValue getIntEncodedValue(String key) {
        return getEncodedValue(key, IntEncodedValue.class);
    }

    @Override
    public DecimalEncodedValue getDecimalEncodedValue(String key) {
        return getEncodedValue(key, DecimalEncodedValue.class);
    }

    @Override
    public StringEncodedValue getStringEncodedValue(String key) {
        return getEncodedValue(key, StringEncodedValue.class);
    }

    @Override
    public <T extends EncodedValue> T getEncodedValue(String key, Class<T> encodedValueType) {
        TagParser prop = parsers.get(key);
        if (prop == null)
            throw new IllegalArgumentException("Cannot find encoded value " + key + " in existing collection: " + parsers);
        return (T) prop.getEncodedValue();
    }

    static String fixWayName(String str) {
        if (str == null)
            return "";
        return str.replaceAll(";[ ]*", ", ");
    }

    static List<FlagEncoder> parseEncoderString(FlagEncoderFactory factory, String encoderList) {
        if (encoderList.contains(":"))
            throw new IllegalArgumentException("EncodingManager does no longer use reflection: instantiate encoders directly.");

        if (!encoderList.equals(encoderList.toLowerCase()))
            throw new IllegalArgumentException("Since 0.7 the EncodingManager does no longer accept upper case vehicles: " + encoderList);

        String[] entries = encoderList.split(",");
        List<FlagEncoder> resultEncoders = new ArrayList<>();

        for (String entry : entries) {
            entry = entry.trim().toLowerCase();
            if (entry.isEmpty())
                continue;

            String entryVal = "";
            if (entry.contains("|")) {
                entryVal = entry;
                entry = entry.split("\\|")[0];
            }
            PMap configuration = new PMap(entryVal);

            FlagEncoder fe = factory.createFlagEncoder(entry, configuration);

            if (configuration.has("version") && fe.getVersion() != configuration.getInt("version", -1))
                throw new IllegalArgumentException("Encoder " + entry + " was used in version "
                        + configuration.getLong("version", -1) + ", but current version is " + fe.getVersion());

            resultEncoders.add(fe);
        }
        return resultEncoders;
    }

    /**
     * Create the EncodingManager from the provided GraphHopper location. Throws an
     * IllegalStateException if it fails. Used if no EncodingManager specified on load.
     */
    public static EncodingManager create(FlagEncoderFactory factory, String ghLoc) {
        Directory dir = new RAMDirectory(ghLoc, true);
        StorableProperties properties = new StorableProperties(dir);
        if (!properties.loadExisting())
            throw new IllegalStateException("Cannot load properties to fetch EncodingManager configuration at: " + dir.getLocation());

        // check encoding for compatibility
        properties.checkVersions(false);
        String acceptStr = properties.get("graph.flag_encoders");

        if (acceptStr.isEmpty())
            throw new IllegalStateException("EncodingManager was not configured. And no one was found in the graph: " + dir.getLocation());

        int bytesForFlags = 4;
        if ("8".equals(properties.get("graph.bytes_for_flags")))
            bytesForFlags = 8;
        EncodingManager.Builder builder = new EncodingManager.Builder();
        builder.addAll(factory, acceptStr, bytesForFlags);
        return builder.build();
    }

    public int getBytesForFlags() {
        return bitsForEdgeFlags / 8;
    }

    /**
     * @return true if the specified encoder is found
     */
    public boolean supports(String encoder) {
        return getEncoder(encoder, false) != null;
    }

    public FlagEncoder getEncoder(String name) {
        return getEncoder(name, true);
    }

    private FlagEncoder getEncoder(String name, boolean throwExc) {
        for (FlagEncoder encoder : edgeEncoders) {
            if (name.equalsIgnoreCase(encoder.toString()))
                return encoder;
        }
        if (throwExc)
            throw new IllegalArgumentException("Encoder for " + name + " not found. Existing: " + toDetailsString());
        return null;
    }

    /**
     * Determine whether a way is routable for one of the added encoders.
     */
    public boolean acceptWay(ReaderWay way, AcceptWay acceptWay) {
        for (AbstractFlagEncoder encoder : edgeEncoders) {
            acceptWay.set(encoder.toString(), encoder.getAccess(way));
        }
        return acceptWay.hasAccepted();
    }

    public static class AcceptWay {
        private Map<String, Access> accessMap;
        boolean hasAccepted = false;

        public AcceptWay() {
            this.accessMap = new HashMap<>(5);
        }

        private Access get(String key) {
            Access res = accessMap.get(key);
            if (res == null)
                throw new IllegalArgumentException("Couldn't fetch access value for key " + key);

            return res;
        }

        private void set(String key, Access access) {
            accessMap.put(key, access);
            if (access != Access.CAN_SKIP)
                hasAccepted = true;
        }

        public boolean hasAccepted() {
            return hasAccepted;
        }
    }

    public enum Access {
        WAY, FERRY, CAN_SKIP;

        boolean isFerry() {
            return this.ordinal() == FERRY.ordinal();
        }

        boolean isWay() {
            return this.ordinal() == WAY.ordinal();
        }

        boolean canSkip() {
            return this.ordinal() == CAN_SKIP.ordinal();
        }
    }

    public long handleRelationTags(ReaderRelation relation, long oldRelationFlags) {
        long flags = 0;
        for (AbstractFlagEncoder encoder : edgeEncoders) {
            flags |= encoder.handleRelationTags(relation, oldRelationFlags);
        }

        return flags;
    }

    /**
     * Processes way properties of different kind to determine speed and direction. Properties are
     * directly encoded in the provided IntsRef.
     *
     * @param relationFlags The preprocessed relation flags is used to influence the way properties.
     */
    public IntsRef handleWayTags(IntsRef ints, ReaderWay way, AcceptWay acceptWay, long relationFlags) {
        for (AbstractFlagEncoder encoder : edgeEncoders) {
            encoder.handleWayTags(ints, way, acceptWay.get(encoder.toString()), relationFlags & encoder.getRelBitMask());
        }

        parser.parse(parsers.values(), ints, way);
        return ints;
    }

//    public long flagsDefault(boolean forward, boolean backward) {
//        long flags = 0;
//        for (AbstractFlagEncoder encoder : edgeEncoders) {
//            flags |= encoder.flagsDefault(forward, backward);
//        }
//        return flags;
//    }
//
//    /**
//     * Reverse flags, to do so all encoders are called.
//     */
//    public long reverseFlags(long flags) {
//        // performance critical
//        int len = edgeEncoders.size();
//        for (int i = 0; i < len; i++) {
//            flags = edgeEncoders.get(i).reverseFlags(flags);
//        }
//        return flags;
//    }

    /**
     * Analyze tags on osm node. Store node tags (barriers etc) for later usage while parsing way.
     */
    public long handleNodeTags(ReaderNode node) {
        long flags = 0;
        for (AbstractFlagEncoder encoder : edgeEncoders) {
            flags |= encoder.handleNodeTags(node);
        }

        return flags;
    }

    /**
     * Method called after edge is created
     */
    public void applyWayTags(ReaderWay way, EdgeIteratorState edge) {
        // storing the road name does not yet depend on the flagEncoder so manage it directly
        if (enableInstructions) {
            // String wayInfo = carFlagEncoder.getWayInfo(way);
            // http://wiki.openstreetmap.org/wiki/Key:name
            String name = "";
            if (!preferredLanguage.isEmpty())
                name = fixWayName(way.getTag("name:" + preferredLanguage));
            if (name.isEmpty())
                name = fixWayName(way.getTag("name"));
            // http://wiki.openstreetmap.org/wiki/Key:ref
            String refName = fixWayName(way.getTag("ref"));
            if (!refName.isEmpty()) {
                if (name.isEmpty())
                    name = refName;
                else
                    name += ", " + refName;
            }

            edge.setName(name);
        }

        for (AbstractFlagEncoder encoder : edgeEncoders) {
            encoder.applyWayTags(way, edge);
        }
    }

    /**
     * The returned list is never empty.
     */
    public List<FlagEncoder> fetchEdgeEncoders() {
        List<FlagEncoder> list = new ArrayList<>();
        list.addAll(edgeEncoders);
        return list;
    }

    public boolean needsTurnCostsSupport() {
        for (FlagEncoder encoder : edgeEncoders) {
            if (encoder.supports(TurnWeighting.class))
                return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int hash = 5;
        hash = 53 * hash + (this.edgeEncoders != null ? this.edgeEncoders.hashCode() : 0);
        return hash;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null)
            return false;

        if (getClass() != obj.getClass())
            return false;

        final EncodingManager other = (EncodingManager) obj;
        if (this.edgeEncoders != other.edgeEncoders
                && (this.edgeEncoders == null || !this.edgeEncoders.equals(other.edgeEncoders))) {
            return false;
        }
        return true;
    }

    @Override
    public String toString() {
        StringBuilder str = new StringBuilder();
        for (FlagEncoder encoder : edgeEncoders) {
            if (str.length() > 0)
                str.append(",");

            str.append(encoder.toString());
        }
        return str.toString();
    }

    void parsersToString(StringBuilder str) {
        for (TagParser p : parsers.values()) {
            if (str.length() > 0)
                str.append(", ");
            str.append(p.getName());
        }
    }

    public String toDetailsString() {
        StringBuilder str = new StringBuilder();
        for (AbstractFlagEncoder encoder : edgeEncoders) {
            if (str.length() > 0)
                str.append(",");

            str.append(encoder.toString())
                    .append("|")
                    .append(encoder.getPropertiesString())
                    .append("|version=")
                    .append(encoder.getVersion());
        }
        parsersToString(str);
        return str.toString();
    }
}
