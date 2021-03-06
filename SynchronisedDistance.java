import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SynchronisedDistance implements DistanceMeasure {

    private double[][] distanceGraph;
    private double[][] shortestDistanceMatrix;

    private Map<Integer, Integer> dict = new HashMap<Integer, Integer>();

    @Override
	public void createSupportData(List<Dataset> ds) {
        System.out.print("Copying data sets ...");
        List<Dataset> dsCopies = new LinkedList<Dataset>();
        for (Dataset d : ds) {
            dsCopies.add(new Dataset(d));
        }
        System.out.print("\rCopied data sets       \n");

        List<Trajectory> all = new LinkedList<Trajectory>();
        for (Dataset d : dsCopies) all.addAll(d.getTrajectories());

        int i = 0;
        for (Trajectory r : all) { this.dict.put(r.id, i); i++; }

        SynchronisedDistance.synchroniseTrajectories(all);
        this.distanceGraph = this.makeDistanceGraph(all);
        this.shortestDistanceMatrix = SynchronisedDistance.computeShortestDistanceMatrix(this.distanceGraph);
    }

    private static void synchroniseTrajectories(List<Trajectory> rs) {
        // Get a set of all timestamps of all existing places within the data set
        Set<Long> ts = new HashSet<Long>();
        for (Trajectory r : rs) {
            ts.addAll(r.getTimestamps());
        }
        
        int synchronised = 1;
        for (Trajectory r : rs) {
            System.out.print("\rSynchronised trajectories ... " + synchronised);

            long minT = Collections.min(r.getTimestamps());
            long maxT = Collections.max(r.getTimestamps());

            // For all timestamps in the data set ...
            for (long t : ts) {
                // If the timestamp is withing the time frame the Trajectory was recorded ...
                if (minT < t && t < maxT) {
                    // If the trajectory has no place for timestamp t ... add an interpolated one
                    if (!r.getTimestamps().contains(t)) {
                        long timeBefore = minT;
                        for (long rT: r.getTimestamps()) {
                            if (rT < t && (t - rT < t - timeBefore || timeBefore == -1)) timeBefore = rT;
                        }
                        Place placeBefore = r.getPlaceAtTime(timeBefore);

                        long timeAfter = maxT;
                        for (long rT: r.getTimestamps()) {
                            if (rT > t && (rT - t < timeAfter - t || timeAfter == -1)) timeAfter = rT;
                        }
                        Place placeAfter = r.getPlaceAtTime(timeAfter);

                        long xInterpolated = placeBefore.getX() + (placeAfter.getX() - placeBefore.getX()) / (timeAfter - timeBefore);
                        long yInterpolated = placeBefore.getY() + (placeAfter.getY() - placeBefore.getY()) / (timeAfter - timeBefore);
                        r.add(t, new Place(xInterpolated, yInterpolated));
                    }
                }
            }

            synchronised++;
        }

        System.out.print("\n");
    }

    private double[][] makeDistanceGraph(List<Trajectory> rs) {
        double[][] distanceGraph = new double[rs.size()][rs.size()];

        int builtIn = 1;
        for (Trajectory r : rs) {
            double percentage = builtIn * 100 / rs.size();
            System.out.print("\rBuilding distance graph ... " + percentage + "%");

            for (Trajectory s : rs) {
                double d;

                if (s == r) {
                    d = 0;
                } else if (SynchronisedDistance.percentContemporary(r, s) > 0) {
                    d = SynchronisedDistance.directDistance(r, s);
                } else {
                    d = Double.POSITIVE_INFINITY;
                }

                distanceGraph[this.dict.get(r.id)][this.dict.get(s.id)] = distanceGraph[this.dict.get(s.id)][this.dict.get(r.id)] = d;
            }

            builtIn++;
        }

        System.out.print("\rBuilt distance graph\n");

        return distanceGraph;
    }

    private static double percentContemporary(Trajectory r, Trajectory s) {
        long rFirstT = Collections.min(r.getTimestamps());
        long rLastT = Collections.max(r.getTimestamps());
        long sFirstT = Collections.min(s.getTimestamps());
        long sLastT = Collections.max(s.getTimestamps());

        long I = Math.max(Math.min(rLastT, sLastT) - Math.max(rFirstT, sFirstT), 0);

        return 100 * Math.min((double)I / (double)(rLastT - rFirstT), (double)I / (double)(sLastT - sFirstT));
    }

    private static double directDistance(Trajectory r, Trajectory s) {
        Set<Long> ot = new HashSet<Long>(r.getTimestamps());
        ot.retainAll(s.getTimestamps());

        double sum = 0.0;
        for (long t : ot) {
            double a1 = Math.pow(r.getPlaceAtTime(t).getX() - s.getPlaceAtTime(t).getX(), 2);
            double a2 = Math.pow(r.getPlaceAtTime(t).getY() - s.getPlaceAtTime(t).getY(), 2);
            sum += (a1 + a2) / Math.pow(ot.size(), 2);
        }

        return Math.sqrt(sum) / SynchronisedDistance.percentContemporary(r, s);
    }

    private static double[][] computeShortestDistanceMatrix(double[][] distanceGraph) {
        int n = distanceGraph.length;

        double[][] shortestDistanceMatrix = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                shortestDistanceMatrix[i][j] = distanceGraph[i][j];
            }
        }

        for (int k = 0; k < n; k++) {
            double percentage = (k + 1) * 100 / n;
            System.out.print("\rComputing shortest distance matrix ... " + percentage + "%");

            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    if (distanceGraph[i][k] + distanceGraph[k][j] < distanceGraph[i][j]) {
                        distanceGraph[i][j] = distanceGraph[i][k] + distanceGraph[k][j];
                    }
                }
            }
        }

        System.out.print("\rComputed shortest distance matrix      \n");

        return distanceGraph;
    }

    @Override
	public void removeImpossibleTrajectoriesFromDataset(Dataset d) {
        System.out.print("\rRemoving unreachable trajectories ...");

        int sizeBefore = d.size();

        int[] visitedMask = new int[d.size()];
        for (int i = 0; i < visitedMask.length; i++) visitedMask[i] = -1;

        int c = 0;
        int largestC = 0;
        int largestCSize = 0;

        for (int i = 0; i < d.size(); i++) {
            if (visitedMask[i] == -1) {
                int n = this.depthFirstSearch(i, c, visitedMask);
                if (n > largestCSize) {
                    largestC = c;
                    largestCSize = n;
                }
                c++;
            }
        }

        for (int i = 0; i < d.size(); i++) {
            if (visitedMask[i] != largestC) {
                List<Trajectory> rs = d.getTrajectories();
                Iterator<Trajectory> rsIter = rs.iterator();
                while (rsIter.hasNext()) {
                    Trajectory r = rsIter.next();
                    if (this.dict.get(r.id) == i) rsIter.remove();
                }
            }
        }

        System.out.print("\rRemoved unreachable trajectories ... " + (d.size() - sizeBefore) + "\n");
    }
    
    public int depthFirstSearch(int v, int c, int[] visitedMask) {
        visitedMask[v] = c;

        double[] connections = this.distanceGraph[v];
        int n = 1;
        for (int i = 0; i < connections.length; i++) {
            if (connections[i] < Double.POSITIVE_INFINITY && visitedMask[i] == -1) {
                int k = this.depthFirstSearch(i, c, visitedMask);
                n += k;
            }
        }

        return n;
    }

	@Override
	public double computeDistance(Trajectory r, Trajectory s) {
        return this.shortestDistanceMatrix[this.dict.get(r.id)][this.dict.get(s.id)];
	}

}