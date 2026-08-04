package kairon.ui.swing.behaviorgraph;

import kairon.behavior.normalize.NormalizedEventType;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.EdgeRenderKind;
import kairon.ui.swing.behaviorgraph.BehaviorGraphRenderModel.NodeRenderData;

import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Routes all structural edges together through deterministic clear channels.
 */
final class BehaviorGraphEdgeRouter {

    private static final double ARROW_LENGTH = 9.0;
    private static final double ARROW_HALF_WIDTH = 4.5;
    private static final double CORNER_RADIUS = 7.0;
    private static final double OBSTACLE_PADDING = 6.0;
    private static final double CHANNEL_CLEARANCE = 8.0;
    private static final double BACKWARD_LANE_GAP = 12.0;
    private static final double VERTICAL_LANE_GAP = 10.0;
    private static final double VERTICAL_RESERVATION_PADDING = 18.0;
    private static final double MINIMUM_COORDINATE = 8.0;
    private static final double FLATNESS = 0.75;
    private static final double EPSILON = 0.000_001;
    private static final int MAX_ROUTING_LANES = 12;
    private static final int MAX_INTERSECTION_CANDIDATES = 48;

    Map<RouteKey, Geometry> route(
            List<RouteRequest> source,
            Map<NormalizedEventType, NodeRenderData> nodes
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(nodes, "nodes");

        List<RouteRequest> requests = new ArrayList<>(source);
        requests.sort(Comparator
                .comparingInt(BehaviorGraphEdgeRouter::priority)
                .thenComparingInt(request ->
                        Math.abs(
                                request.to().level()
                                        - request.from().level()
                        ))
                .thenComparing(RouteRequest::fromType)
                .thenComparing(RouteRequest::toType));

        RoutingBounds bounds = RoutingBounds.from(nodes);
        LaneAllocation backwardHorizontalLanes = allocateLanes(
                requests.stream()
                        .filter(request ->
                                request.kind()
                                        == EdgeRenderKind.BACKWARD)
                        .map(request -> new IntervalRoute(
                                request,
                                Math.min(
                                        request.from().centerX(),
                                        request.to().centerX()
                                ),
                                Math.max(
                                        request.from().centerX(),
                                        request.to().centerX()
                                )
                        ))
                        .toList()
        );
        Map<Integer, LaneAllocation> verticalLanes =
                verticalLanes(requests, bounds);

        Map<RouteKey, Geometry> result = new LinkedHashMap<>();
        RoutingIndex selected = new RoutingIndex();
        for (RouteRequest request : requests) {
            List<Candidate> candidates = candidates(
                    request,
                    backwardHorizontalLanes,
                    verticalLanes,
                    bounds
            );
            Candidate best = null;
            RouteScore bestScore = null;
            for (Candidate candidate : candidates) {
                RouteScore score = new RouteScore(
                        obstacleHits(
                                candidate.segments(),
                                request,
                                nodes
                        ),
                        edgeCrossings(
                                candidate,
                                request,
                                selected,
                                nodes
                        ),
                        candidate.length(),
                        candidate.id()
                );
                if (bestScore == null
                        || score.compareTo(bestScore) < 0) {
                    best = candidate;
                    bestScore = score;
                }
            }
            Objects.requireNonNull(best, "edge route candidate");
            RouteKey key = request.key();
            result.put(key, best.geometry());
            selected.add(new RoutedPath(
                    request,
                    best.segments(),
                    best.bounds()
            ));
        }
        return Collections.unmodifiableMap(result);
    }

    private static int priority(RouteRequest request) {
        return switch (request.kind()) {
            case FORWARD -> 0;
            case SAME_LEVEL -> 1;
            case BACKWARD -> 2;
            case SELF -> 3;
        };
    }

    private static List<Candidate> candidates(
            RouteRequest request,
            LaneAllocation backwardHorizontalLanes,
            Map<Integer, LaneAllocation> verticalLanes,
            RoutingBounds bounds
    ) {
        return switch (request.kind()) {
            case FORWARD -> forwardCandidates(
                    request,
                    verticalLanes.get(request.to().level())
            );
            case SAME_LEVEL -> List.of(sameLevelCandidate(
                    request,
                    verticalLanes.get(request.from().level())
            ));
            case BACKWARD -> backwardCandidates(
                    request,
                    backwardHorizontalLanes,
                    verticalLanes,
                    bounds
            );
            case SELF -> List.of(selfCandidate(request));
        };
    }

    private static List<Candidate> forwardCandidates(
            RouteRequest request,
            LaneAllocation verticalLanes
    ) {
        double deltaY = request.to().centerY()
                - request.from().centerY();
        int preferredDirection = deltaY < 0.0 ? -1 : 1;
        return List.of(
                forwardCandidate(
                        request,
                        verticalLanes,
                        preferredDirection,
                        0
                ),
                forwardCandidate(
                        request,
                        verticalLanes,
                        -preferredDirection,
                        1
                )
        );
    }

    private static Candidate forwardCandidate(
            RouteRequest request,
            LaneAllocation verticalLanes,
            int direction,
            int id
    ) {
        NodeRenderData from = request.from();
        NodeRenderData to = request.to();
        double sourceRadius = radius(from);
        double targetRadius = radius(to);
        double diagonal = sourceRadius / Math.sqrt(2.0);
        Point2D.Double start = new Point2D.Double(
                from.centerX() + diagonal,
                from.centerY() + direction * diagonal
        );
        double sourceObstacleY = direction < 0
                ? Math.min(
                        from.circleBounds().getMinY(),
                        from.labelBounds().getMinY()
                )
                : Math.max(
                        from.circleBounds().getMaxY(),
                        from.labelBounds().getMaxY()
                );
        double channelY = sourceObstacleY
                + direction * CHANNEL_CLEARANCE;
        double beforeLabelX = from.labelBounds().getMinX()
                - CHANNEL_CLEARANCE;
        double afterLabelX = from.labelBounds().getMaxX()
                + CHANNEL_CLEARANCE;
        int lane = lane(verticalLanes, request.key());
        int laneCount = laneCount(verticalLanes);
        double targetTrunkX = betweenColumnsTrunkX(
                from,
                to,
                lane,
                laneCount
        );
        Point2D.Double end = new Point2D.Double(
                to.centerX() - targetRadius,
                to.centerY()
        );
        return orthogonalCandidate(
                id,
                List.of(
                        start,
                        new Point2D.Double(beforeLabelX, channelY),
                        new Point2D.Double(afterLabelX, channelY),
                        new Point2D.Double(targetTrunkX, channelY),
                        new Point2D.Double(targetTrunkX, end.y),
                        end
                )
        );
    }

    private static List<Candidate> backwardCandidates(
            RouteRequest request,
            LaneAllocation horizontalLanes,
            Map<Integer, LaneAllocation> verticalLanes,
            RoutingBounds bounds
    ) {
        int lane = horizontalLanes.lane(request.key());
        int laneCount = Math.max(
                1,
                horizontalLanes.laneCount()
        );
        LaneAllocation sourceVerticalLanes =
                verticalLanes.get(request.from().level());
        LaneAllocation targetVerticalLanes =
                verticalLanes.get(request.to().level());
        double topAvailable = Math.max(
                1.0,
                bounds.minimumY()
                        - MINIMUM_COORDINATE
                        - CHANNEL_CLEARANCE
        );
        double topGap = Math.min(
                BACKWARD_LANE_GAP,
                topAvailable / laneCount
        );
        double outwardIndex = laneCount - lane;
        double topY = bounds.minimumY()
                - CHANNEL_CLEARANCE
                - outwardIndex * topGap;
        double bottomY = bounds.maximumY()
                + CHANNEL_CLEARANCE
                + outwardIndex * BACKWARD_LANE_GAP;
        return List.of(
                backwardCandidate(
                        request,
                        sourceVerticalLanes,
                        targetVerticalLanes,
                        topY,
                        -1,
                        0
                ),
                backwardCandidate(
                        request,
                        sourceVerticalLanes,
                        targetVerticalLanes,
                        bottomY,
                        1,
                        1
                )
        );
    }

    private static Candidate backwardCandidate(
            RouteRequest request,
            LaneAllocation sourceVerticalLanes,
            LaneAllocation targetVerticalLanes,
            double channelY,
            int direction,
            int id
    ) {
        NodeRenderData from = request.from();
        NodeRenderData to = request.to();
        double sourceRadius = radius(from);
        double targetRadius = radius(to);
        double sourceDiagonal = sourceRadius / Math.sqrt(2.0);
        double targetDiagonal = targetRadius / Math.sqrt(2.0);
        Point2D.Double start = new Point2D.Double(
                from.centerX() - sourceDiagonal,
                from.centerY() + direction * sourceDiagonal
        );
        Point2D.Double end = new Point2D.Double(
                to.centerX() - targetDiagonal,
                to.centerY() + direction * targetDiagonal
        );
        RouteKey key = request.key();
        double sourceTrunkX = leftTrunkX(
                from,
                lane(sourceVerticalLanes, key),
                laneCount(sourceVerticalLanes)
        );
        double targetTrunkX = leftTrunkX(
                to,
                lane(targetVerticalLanes, key),
                laneCount(targetVerticalLanes)
        );
        return orthogonalCandidate(
                id,
                List.of(
                        start,
                        new Point2D.Double(sourceTrunkX, start.y),
                        new Point2D.Double(sourceTrunkX, channelY),
                        new Point2D.Double(targetTrunkX, channelY),
                        new Point2D.Double(targetTrunkX, end.y),
                        end
                )
        );
    }

    private static Candidate sameLevelCandidate(
            RouteRequest request,
            LaneAllocation verticalLanes
    ) {
        int lane = lane(verticalLanes, request.key());
        int laneCount = laneCount(verticalLanes);
        NodeRenderData from = request.from();
        NodeRenderData to = request.to();
        int direction = to.centerY() >= from.centerY() ? 1 : -1;
        double sourceDiagonal = radius(from) / Math.sqrt(2.0);
        double targetDiagonal = radius(to) / Math.sqrt(2.0);
        Point2D.Double start = new Point2D.Double(
                from.centerX() - sourceDiagonal,
                from.centerY() + direction * sourceDiagonal
        );
        Point2D.Double end = new Point2D.Double(
                to.centerX() - targetDiagonal,
                to.centerY() - direction * targetDiagonal
        );
        double trunkX = leftTrunkX(
                Math.min(columnLeftX(from), columnLeftX(to)),
                lane,
                laneCount
        );
        return orthogonalCandidate(
                0,
                List.of(
                        start,
                        new Point2D.Double(trunkX, start.y),
                        new Point2D.Double(trunkX, end.y),
                        end
                )
        );
    }

    private static Candidate selfCandidate(RouteRequest request) {
        NodeRenderData node = request.from();
        double radius = radius(node);
        Point2D.Double start = new Point2D.Double(
                node.centerX() - radius * 0.75,
                node.centerY() - radius * 0.65
        );
        Point2D.Double end = new Point2D.Double(
                node.centerX() + radius * 0.15,
                node.centerY() - radius
        );
        Point2D.Double control1 = new Point2D.Double(
                Math.max(
                        MINIMUM_COORDINATE,
                        node.centerX() - radius - 34.0
                ),
                Math.max(
                        MINIMUM_COORDINATE,
                        node.centerY() - radius - 40.0
                )
        );
        Point2D.Double control2 = new Point2D.Double(
                node.centerX() + 5.0,
                Math.max(
                        MINIMUM_COORDINATE,
                        node.centerY() - radius - 46.0
                )
        );
        Path2D.Double path = new Path2D.Double();
        path.moveTo(start.x, start.y);
        path.curveTo(
                control1.x,
                control1.y,
                control2.x,
                control2.y,
                end.x,
                end.y
        );
        Geometry geometry = new Geometry(
                path,
                arrowHead(control2, end)
        );
        List<Line2D.Double> segments = segments(path);
        return new Candidate(
                0,
                geometry,
                segments,
                bounds(segments),
                length(segments)
        );
    }

    private static Candidate orthogonalCandidate(
            int id,
            List<Point2D.Double> sourcePoints
    ) {
        List<Point2D.Double> points = distinctPoints(sourcePoints);
        if (points.size() < 2) {
            throw new IllegalArgumentException(
                    "an edge route requires two distinct points"
            );
        }
        Path2D.Double path = roundedPath(points);
        Point2D.Double tip = points.getLast();
        Point2D.Double previous = points.get(points.size() - 2);
        Geometry geometry = new Geometry(
                path,
                arrowHead(previous, tip)
        );
        List<Line2D.Double> segments = segments(path);
        return new Candidate(
                id,
                geometry,
                segments,
                bounds(segments),
                length(segments)
        );
    }

    private static Path2D.Double roundedPath(
            List<Point2D.Double> points
    ) {
        Path2D.Double path = new Path2D.Double();
        Point2D.Double first = points.getFirst();
        path.moveTo(first.x, first.y);
        for (int index = 1; index < points.size() - 1; index++) {
            Point2D.Double previous = points.get(index - 1);
            Point2D.Double corner = points.get(index);
            Point2D.Double next = points.get(index + 1);
            double incomingLength = previous.distance(corner);
            double outgoingLength = corner.distance(next);
            double radius = Math.min(
                    CORNER_RADIUS,
                    Math.min(incomingLength, outgoingLength) / 2.0
            );
            Point2D.Double before = pointToward(
                    corner,
                    previous,
                    radius
            );
            Point2D.Double after = pointToward(
                    corner,
                    next,
                    radius
            );
            path.lineTo(before.x, before.y);
            path.quadTo(
                    corner.x,
                    corner.y,
                    after.x,
                    after.y
            );
        }
        Point2D.Double last = points.getLast();
        path.lineTo(last.x, last.y);
        return path;
    }

    private static Point2D.Double pointToward(
            Point2D.Double from,
            Point2D.Double to,
            double distance
    ) {
        double total = from.distance(to);
        if (total <= EPSILON) {
            return new Point2D.Double(from.x, from.y);
        }
        double ratio = distance / total;
        return new Point2D.Double(
                from.x + (to.x - from.x) * ratio,
                from.y + (to.y - from.y) * ratio
        );
    }

    private static List<Point2D.Double> distinctPoints(
            List<Point2D.Double> source
    ) {
        List<Point2D.Double> result = new ArrayList<>(source.size());
        for (Point2D.Double point : source) {
            if (result.isEmpty()
                    || result.getLast().distance(point) > EPSILON) {
                result.add(new Point2D.Double(point.x, point.y));
            }
        }
        return List.copyOf(result);
    }

    private static double betweenColumnsTrunkX(
            NodeRenderData from,
            NodeRenderData to,
            int lane,
            int laneCount
    ) {
        double leftBoundary = from.labelBounds().getMaxX()
                + CHANNEL_CLEARANCE;
        double rightBoundary = columnLeftX(to)
                - CHANNEL_CLEARANCE;
        double available = Math.max(
                1.0,
                rightBoundary - leftBoundary
        );
        double gap = Math.min(
                VERTICAL_LANE_GAP,
                available / Math.max(1, laneCount)
        );
        return Math.max(
                leftBoundary,
                rightBoundary - (lane + 1) * gap
        );
    }

    private static double leftTrunkX(
            NodeRenderData node,
            int lane,
            int laneCount
    ) {
        return leftTrunkX(
                columnLeftX(node),
                lane,
                laneCount
        );
    }

    private static double leftTrunkX(
            double left,
            int lane,
            int laneCount
    ) {
        double available = Math.max(
                1.0,
                left - MINIMUM_COORDINATE - CHANNEL_CLEARANCE
        );
        double gap = Math.min(
                VERTICAL_LANE_GAP,
                available / Math.max(1, laneCount)
        );
        return left
                - CHANNEL_CLEARANCE
                - (lane + 1) * gap;
    }

    private static double columnLeftX(NodeRenderData node) {
        return node.centerX()
                - LayeredBehaviorGraphLayoutEngine.CURRENT_NODE_RADIUS;
    }

    private static int lane(
            LaneAllocation lanes,
            RouteKey key
    ) {
        return lanes == null ? 0 : lanes.lane(key);
    }

    private static int laneCount(LaneAllocation lanes) {
        return lanes == null
                ? 1
                : Math.max(1, lanes.laneCount());
    }

    private static Map<Integer, LaneAllocation> verticalLanes(
            List<RouteRequest> requests,
            RoutingBounds bounds
    ) {
        Map<Integer, List<IntervalRoute>> byLevel =
                new LinkedHashMap<>();
        for (RouteRequest request : requests) {
            double minimumY = Math.min(
                    request.from().centerY(),
                    request.to().centerY()
            ) - VERTICAL_RESERVATION_PADDING;
            double maximumY = Math.max(
                    request.from().centerY(),
                    request.to().centerY()
            ) + VERTICAL_RESERVATION_PADDING;
            switch (request.kind()) {
                case FORWARD -> addVerticalReservation(
                        byLevel,
                        request.to().level(),
                        request,
                        minimumY,
                        maximumY
                );
                case SAME_LEVEL -> addVerticalReservation(
                        byLevel,
                        request.from().level(),
                        request,
                        minimumY,
                        maximumY
                );
                case BACKWARD -> {
                    double outerMinimum = bounds.minimumY()
                            - VERTICAL_RESERVATION_PADDING;
                    double outerMaximum = bounds.maximumY()
                            + VERTICAL_RESERVATION_PADDING;
                    addVerticalReservation(
                            byLevel,
                            request.from().level(),
                            request,
                            outerMinimum,
                            outerMaximum
                    );
                    addVerticalReservation(
                            byLevel,
                            request.to().level(),
                            request,
                            outerMinimum,
                            outerMaximum
                    );
                }
                case SELF -> {
                    // Self edges use local loops instead of vertical trunks.
                }
            }
        }
        Map<Integer, LaneAllocation> result = new LinkedHashMap<>();
        byLevel.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> result.put(
                        entry.getKey(),
                        allocateLanes(entry.getValue())
                ));
        return Collections.unmodifiableMap(result);
    }

    private static void addVerticalReservation(
            Map<Integer, List<IntervalRoute>> byLevel,
            int level,
            RouteRequest request,
            double minimumY,
            double maximumY
    ) {
        byLevel.computeIfAbsent(
                level,
                ignored -> new ArrayList<>()
        ).add(new IntervalRoute(
                request,
                minimumY,
                maximumY
        ));
    }

    private static LaneAllocation allocateLanes(
            List<IntervalRoute> source
    ) {
        if (source.isEmpty()) {
            return LaneAllocation.empty();
        }
        List<IntervalRoute> intervals = new ArrayList<>(source);
        intervals.sort(Comparator
                .comparingDouble(IntervalRoute::length)
                .reversed()
                .thenComparingDouble(IntervalRoute::start)
                .thenComparingDouble(IntervalRoute::end)
                .thenComparing(interval ->
                        interval.request().fromType())
                .thenComparing(interval ->
                        interval.request().toType()));

        List<List<IntervalRoute>> lanes = new ArrayList<>();
        Map<RouteKey, Integer> laneByEdge = new LinkedHashMap<>();
        for (IntervalRoute interval : intervals) {
            int selectedLane = -1;
            for (int lane = 0; lane < lanes.size(); lane++) {
                if (lanes.get(lane).stream().noneMatch(existing ->
                        overlaps(interval, existing))) {
                    selectedLane = lane;
                    break;
                }
            }
            if (selectedLane < 0) {
                selectedLane = lanes.size();
                lanes.add(new ArrayList<>());
            }
            lanes.get(selectedLane).add(interval);
            laneByEdge.put(interval.request().key(), selectedLane);
        }
        int laneCount = Math.min(
                MAX_ROUTING_LANES,
                lanes.size()
        );
        if (lanes.size() > MAX_ROUTING_LANES) {
            laneByEdge.replaceAll((key, lane) ->
                    lane % MAX_ROUTING_LANES);
        }
        return new LaneAllocation(laneByEdge, laneCount);
    }

    private static boolean overlaps(
            IntervalRoute first,
            IntervalRoute second
    ) {
        return first.start() < second.end() - EPSILON
                && second.start() < first.end() - EPSILON;
    }

    private static int obstacleHits(
            List<Line2D.Double> segments,
            RouteRequest request,
            Map<NormalizedEventType, NodeRenderData> nodes
    ) {
        int hits = 0;
        for (NodeRenderData node : nodes.values()) {
            if (intersects(
                    grow(node.labelBounds(), OBSTACLE_PADDING),
                    segments
            )) {
                hits++;
            }
            if (!node.eventType().equals(request.fromType())
                    && !node.eventType().equals(request.toType())
                    && intersects(
                            grow(
                                    node.circleBounds().getBounds2D(),
                                    OBSTACLE_PADDING
                            ),
                            segments
                    )) {
                hits++;
            }
        }
        return hits;
    }

    private static int edgeCrossings(
            Candidate candidate,
            RouteRequest request,
            RoutingIndex selected,
            Map<NormalizedEventType, NodeRenderData> nodes
    ) {
        int crossings = 0;
        for (RoutedPath routed : selected.intersecting(
                candidate.bounds()
        )) {
            for (Line2D.Double first : candidate.segments()) {
                for (Line2D.Double second : routed.segments()) {
                    Point2D.Double intersection = properIntersection(
                            first,
                            second
                    );
                    if (intersection == null
                            || nearSharedEndpoint(
                                    intersection,
                                    request,
                                    routed.request(),
                                    nodes
                            )) {
                        continue;
                    }
                    crossings++;
                }
            }
        }
        return crossings;
    }

    private static Point2D.Double properIntersection(
            Line2D.Double first,
            Line2D.Double second
    ) {
        double firstDx = first.x2 - first.x1;
        double firstDy = first.y2 - first.y1;
        double secondDx = second.x2 - second.x1;
        double secondDy = second.y2 - second.y1;
        double denominator = firstDx * secondDy
                - firstDy * secondDx;
        if (Math.abs(denominator) <= EPSILON) {
            return null;
        }
        double offsetX = second.x1 - first.x1;
        double offsetY = second.y1 - first.y1;
        double firstRatio = (
                offsetX * secondDy - offsetY * secondDx
        ) / denominator;
        double secondRatio = (
                offsetX * firstDy - offsetY * firstDx
        ) / denominator;
        if (firstRatio <= EPSILON
                || firstRatio >= 1.0 - EPSILON
                || secondRatio <= EPSILON
                || secondRatio >= 1.0 - EPSILON) {
            return null;
        }
        return new Point2D.Double(
                first.x1 + firstRatio * firstDx,
                first.y1 + firstRatio * firstDy
        );
    }

    private static boolean nearSharedEndpoint(
            Point2D.Double intersection,
            RouteRequest first,
            RouteRequest second,
            Map<NormalizedEventType, NodeRenderData> nodes
    ) {
        for (NormalizedEventType endpoint : List.of(
                first.fromType(),
                first.toType()
        )) {
            boolean shared = endpoint.equals(second.fromType())
                    || endpoint.equals(second.toType());
            NodeRenderData node = nodes.get(endpoint);
            if (shared
                    && node != null
                    && intersection.distance(
                            node.centerX(),
                            node.centerY()
                    ) <= radius(node) + CHANNEL_CLEARANCE * 2.0) {
                return true;
            }
        }
        return false;
    }

    private static Rectangle2D.Double bounds(
            List<Line2D.Double> segments
    ) {
        if (segments.isEmpty()) {
            return new Rectangle2D.Double();
        }
        double minimumX = Double.POSITIVE_INFINITY;
        double minimumY = Double.POSITIVE_INFINITY;
        double maximumX = Double.NEGATIVE_INFINITY;
        double maximumY = Double.NEGATIVE_INFINITY;
        for (Line2D.Double segment : segments) {
            minimumX = Math.min(
                    minimumX,
                    Math.min(segment.x1, segment.x2)
            );
            minimumY = Math.min(
                    minimumY,
                    Math.min(segment.y1, segment.y2)
            );
            maximumX = Math.max(
                    maximumX,
                    Math.max(segment.x1, segment.x2)
            );
            maximumY = Math.max(
                    maximumY,
                    Math.max(segment.y1, segment.y2)
            );
        }
        return new Rectangle2D.Double(
                minimumX,
                minimumY,
                maximumX - minimumX,
                maximumY - minimumY
        );
    }

    private static boolean intersects(
            Rectangle2D rectangle,
            List<Line2D.Double> segments
    ) {
        for (Line2D.Double segment : segments) {
            if (rectangle.intersectsLine(segment)) {
                return true;
            }
        }
        return false;
    }

    private static Rectangle2D.Double grow(
            Rectangle2D rectangle,
            double amount
    ) {
        return new Rectangle2D.Double(
                rectangle.getX() - amount,
                rectangle.getY() - amount,
                rectangle.getWidth() + amount * 2.0,
                rectangle.getHeight() + amount * 2.0
        );
    }

    private static List<Line2D.Double> segments(Path2D.Double path) {
        FlatteningPathIterator iterator = new FlatteningPathIterator(
                path.getPathIterator(null),
                FLATNESS
        );
        List<Line2D.Double> result = new ArrayList<>();
        double[] coordinates = new double[6];
        double previousX = 0.0;
        double previousY = 0.0;
        while (!iterator.isDone()) {
            int type = iterator.currentSegment(coordinates);
            if (type == PathIterator.SEG_MOVETO) {
                previousX = coordinates[0];
                previousY = coordinates[1];
            } else if (type == PathIterator.SEG_LINETO) {
                result.add(new Line2D.Double(
                        previousX,
                        previousY,
                        coordinates[0],
                        coordinates[1]
                ));
                previousX = coordinates[0];
                previousY = coordinates[1];
            }
            iterator.next();
        }
        return List.copyOf(result);
    }

    private static double length(List<Line2D.Double> segments) {
        return segments.stream()
                .mapToDouble(segment -> segment.getP1()
                        .distance(segment.getP2()))
                .sum();
    }

    private static double radius(NodeRenderData node) {
        return node.circleBounds().getWidth() / 2.0;
    }

    private static Path2D.Double arrowHead(
            Point2D.Double previous,
            Point2D.Double tip
    ) {
        double dx = tip.x - previous.x;
        double dy = tip.y - previous.y;
        double length = Math.hypot(dx, dy);
        if (length < EPSILON) {
            dx = 1.0;
            dy = 0.0;
            length = 1.0;
        }
        double unitX = dx / length;
        double unitY = dy / length;
        double baseX = tip.x - unitX * ARROW_LENGTH;
        double baseY = tip.y - unitY * ARROW_LENGTH;
        double perpendicularX = -unitY * ARROW_HALF_WIDTH;
        double perpendicularY = unitX * ARROW_HALF_WIDTH;

        Path2D.Double arrow = new Path2D.Double();
        arrow.moveTo(tip.x, tip.y);
        arrow.lineTo(
                baseX + perpendicularX,
                baseY + perpendicularY
        );
        arrow.lineTo(
                baseX - perpendicularX,
                baseY - perpendicularY
        );
        arrow.closePath();
        return arrow;
    }

    private static final class RoutingIndex {

        private final List<RoutedPath> paths = new ArrayList<>();

        private void add(RoutedPath path) {
            paths.add(path);
        }

        private List<RoutedPath> intersecting(
                Rectangle2D bounds
        ) {
            List<RoutedPath> result = new ArrayList<>();
            int minimumIndex = Math.max(
                    0,
                    paths.size() - MAX_INTERSECTION_CANDIDATES
            );
            for (int index = paths.size() - 1;
                    index >= minimumIndex;
                    index--) {
                RoutedPath path = paths.get(index);
                if (bounds.intersects(path.bounds())) {
                    result.add(path);
                }
            }
            return List.copyOf(result);
        }
    }

    record RouteRequest(
            NormalizedEventType fromType,
            NormalizedEventType toType,
            NodeRenderData from,
            NodeRenderData to,
            EdgeRenderKind kind
    ) {

        RouteRequest {
            Objects.requireNonNull(fromType, "fromType");
            Objects.requireNonNull(toType, "toType");
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(kind, "kind");
        }

        RouteKey key() {
            return new RouteKey(fromType, toType);
        }
    }

    record RouteKey(
            NormalizedEventType from,
            NormalizedEventType to
    ) {

        RouteKey {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    record Geometry(
            Path2D.Double path,
            Path2D.Double arrowHead
    ) {

        Geometry {
            path = copy(path, "path");
            arrowHead = copy(arrowHead, "arrowHead");
        }

        private static Path2D.Double copy(
                Path2D.Double value,
                String name
        ) {
            Objects.requireNonNull(value, name);
            return (Path2D.Double) value.clone();
        }
    }

    private record Candidate(
            int id,
            Geometry geometry,
            List<Line2D.Double> segments,
            Rectangle2D.Double bounds,
            double length
    ) {
    }

    private record RoutedPath(
            RouteRequest request,
            List<Line2D.Double> segments,
            Rectangle2D.Double bounds
    ) {
    }

    private record RouteScore(
            int obstacleHits,
            int crossings,
            double length,
            int candidateId
    ) implements Comparable<RouteScore> {

        @Override
        public int compareTo(RouteScore other) {
            int result = Integer.compare(
                    obstacleHits,
                    other.obstacleHits
            );
            if (result == 0) {
                result = Integer.compare(
                        crossings,
                        other.crossings
                );
            }
            if (result == 0) {
                result = Double.compare(length, other.length);
            }
            if (result == 0) {
                result = Integer.compare(
                        candidateId,
                        other.candidateId
                );
            }
            return result;
        }
    }

    private record IntervalRoute(
            RouteRequest request,
            double start,
            double end
    ) {

        private double length() {
            return end - start;
        }
    }

    private record LaneAllocation(
            Map<RouteKey, Integer> laneByEdge,
            int laneCount
    ) {

        private LaneAllocation {
            laneByEdge = Map.copyOf(laneByEdge);
            if (laneCount < 0) {
                throw new IllegalArgumentException(
                        "laneCount must be nonnegative"
                );
            }
        }

        private static LaneAllocation empty() {
            return new LaneAllocation(Map.of(), 0);
        }

        private int lane(RouteKey key) {
            return laneByEdge.getOrDefault(key, 0);
        }
    }

    private record RoutingBounds(
            double minimumY,
            double maximumY
    ) {

        private static RoutingBounds from(
                Map<NormalizedEventType, NodeRenderData> nodes
        ) {
            double minimum = nodes.values().stream()
                    .mapToDouble(node -> Math.min(
                            node.circleBounds().getMinY(),
                            node.labelBounds().getMinY()
                    ))
                    .min()
                    .orElse(
                            LayeredBehaviorGraphLayoutEngine
                                    .CANVAS_MARGIN
                    );
            double maximum = nodes.values().stream()
                    .mapToDouble(node -> Math.max(
                            node.circleBounds().getMaxY(),
                            node.labelBounds().getMaxY()
                    ))
                    .max()
                    .orElse(
                            LayeredBehaviorGraphLayoutEngine
                                    .CANVAS_MARGIN
                    );
            return new RoutingBounds(minimum, maximum);
        }
    }
}
