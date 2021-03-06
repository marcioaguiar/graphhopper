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
package com.graphhopper.util;

import com.graphhopper.PathWrapper;
import com.graphhopper.routing.Path;
import com.graphhopper.util.details.PathDetail;
import com.graphhopper.util.details.PathDetailsBuilderFactory;
import com.graphhopper.util.exceptions.ConnectionNotFoundException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This class merges multiple {@link Path} objects into one continues object that
 * can be used in the {@link PathWrapper}. There will be a Path between every waypoint.
 * So for two waypoints there will be only one Path object. For three waypoints there will be
 * two Path objects.
 * <p>
 * The instructions are generated per Path object and are merged into one continues InstructionList.
 * The PointList per Path object are merged and optionally simplified.
 *
 * @author Peter Karich
 * @author ratrun
 * @author Robin Boldt
 */
public class PathMerger {
    private static final DouglasPeucker DP = new DouglasPeucker();
    private boolean enableInstructions = true;
    private boolean simplifyResponse = true;
    private DouglasPeucker douglasPeucker = DP;
    private boolean calcPoints = true;
    private PathDetailsBuilderFactory calculatorFactory;

    public PathMerger setCalcPoints(boolean calcPoints) {
        this.calcPoints = calcPoints;
        return this;
    }

    public PathMerger setDouglasPeucker(DouglasPeucker douglasPeucker) {
        this.douglasPeucker = douglasPeucker;
        return this;
    }

    public PathMerger setPathDetailsBuilderFactory(PathDetailsBuilderFactory calculatorFactory) {
        this.calculatorFactory = calculatorFactory;
        return this;
    }

    public PathMerger setSimplifyResponse(boolean simplifyRes) {
        this.simplifyResponse = simplifyRes;
        return this;
    }

    public PathMerger setEnableInstructions(boolean enableInstructions) {
        this.enableInstructions = enableInstructions;
        return this;
    }

    public void doWork(PathWrapper altRsp, List<Path> paths, Translation tr) {
        int origPoints = 0;
        long fullTimeInMillis = 0;
        double fullWeight = 0;
        double fullDistance = 0;
        boolean allFound = true;

        InstructionList fullInstructions = new InstructionList(tr);
        PointList fullPoints = PointList.EMPTY;
        List<String> description = new ArrayList<String>();
        for (int pathIndex = 0; pathIndex < paths.size(); pathIndex++) {
            Path path = paths.get(pathIndex);
            description.addAll(path.getDescription());
            fullTimeInMillis += path.getTime();
            fullDistance += path.getDistance();
            fullWeight += path.getWeight();
            if (enableInstructions) {
                InstructionList il = path.calcInstructions(tr);

                if (!il.isEmpty()) {
                    fullInstructions.addAll(il);

                    // for all paths except the last replace the FinishInstruction with a ViaInstructionn
                    if (pathIndex + 1 < paths.size()) {
                        ViaInstruction newInstr = new ViaInstruction(fullInstructions.get(fullInstructions.size() - 1));
                        newInstr.setViaCount(pathIndex + 1);
                        fullInstructions.replaceLast(newInstr);
                    }
                }

            }
            if (calcPoints || enableInstructions) {
                PointList tmpPoints = path.calcPoints();
                if (fullPoints.isEmpty())
                    fullPoints = new PointList(tmpPoints.size(), tmpPoints.is3D());

                fullPoints.add(tmpPoints);
                altRsp.addPathDetails(path.calcDetails(calculatorFactory, origPoints));
                origPoints += tmpPoints.size();
            }

            allFound = allFound && path.isFound();
        }

        if (!fullPoints.isEmpty()) {
            String debug = altRsp.getDebugInfo() + ", simplify (" + origPoints + "->" + fullPoints.getSize() + ")";
            altRsp.addDebugInfo(debug);
            if (fullPoints.is3D)
                calcAscendDescend(altRsp, fullPoints);
        }

        if (enableInstructions)
            altRsp.setInstructions(fullInstructions);

        if (!allFound) {
            altRsp.addError(new ConnectionNotFoundException("Connection between locations not found", Collections.<String, Object>emptyMap()));
        }

        altRsp.setDescription(description).
                setPoints(fullPoints).
                setRouteWeight(fullWeight).
                setDistance(fullDistance).
                setTime(fullTimeInMillis);

        if (allFound && simplifyResponse && (calcPoints || enableInstructions)) {
            PathSimplification ps = new PathSimplification(altRsp, douglasPeucker, enableInstructions);
            ps.simplify();
        }
    }

    /**
     * Merges <code>otherDetails</code> into the <code>pathDetails</code>.
     * <p>
     * This method makes sure that Entry list around via points are merged correctly.
     * See #1091 and the misplaced PathDetail after waypoints.
     */
    public static void merge(List<PathDetail> pathDetails, List<PathDetail> otherDetails) {
        // Make sure that the PathDetail list is merged correctly at via points
        if (!pathDetails.isEmpty() && !otherDetails.isEmpty()) {
            PathDetail lastDetail = pathDetails.get(pathDetails.size() - 1);
            if (lastDetail.getValue().equals(otherDetails.get(0).getValue())) {
                lastDetail.setLast(otherDetails.get(0).getLast());
                otherDetails.remove(0);
            }
        }

        pathDetails.addAll(otherDetails);
    }

    private void calcAscendDescend(final PathWrapper rsp, final PointList pointList) {
        double ascendMeters = 0;
        double descendMeters = 0;
        double lastEle = pointList.getElevation(0);
        for (int i = 1; i < pointList.size(); ++i) {
            double ele = pointList.getElevation(i);
            double diff = Math.abs(ele - lastEle);

            if (ele > lastEle)
                ascendMeters += diff;
            else
                descendMeters += diff;

            lastEle = ele;

        }
        rsp.setAscend(ascendMeters);
        rsp.setDescend(descendMeters);
    }
}