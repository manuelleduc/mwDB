package ml;

import org.junit.Test;
import org.mwdb.*;
import org.mwdb.gmm.KGaussianNode;
import org.mwdb.task.NoopScheduler;

import java.util.Random;

public class GaussianNonInvertTest {
    @Test
    public void Singularity() {
        KGraph graph = GraphBuilder.builder().withFactory(new GaussianNodeFactory()).withScheduler(new NoopScheduler()).build();
        graph.connect(new KCallback<Boolean>() {
            @Override
            public void on(Boolean result) {
                double[] data = new double[3];
                double[] datan = new double[4];

                Random rand = new Random();

                KGaussianNode node1 =  (KGaussianNode) graph.newNode(0,0,"GaussianNode");
                KGaussianNode node2 =  (KGaussianNode) graph.newNode(0,0,"GaussianNode");

                for (int i = 0; i < 1000; i++) {
                    data[0] = 8 + rand.nextDouble() * 4; //avg =10, [8,12]
                    data[1] = 90 + rand.nextDouble() * 20; //avg=100 [90,110]
                    data[2] = -60 + rand.nextDouble() * 20; //avg=-50 [-60,-40]

                    datan[0] = data[0];
                    datan[1] = data[1];
                    datan[2] = data[2];
                    datan[3] = 0*data[0]+0*data[1]+0*data[2];

                    node1.learn(data);
                    node2.learn(datan);
                }

                double[] avg= node1.getAvg();
                double[] avg2= node2.getAvg();

                printd(avg);
                printd(avg2);

                data[0]=10;
                data[1]=100;
                data[2]=-60;

                datan[0] = data[0];
                datan[1] = data[1];
                datan[2] = data[2];
                datan[3] =  0*data[0]+0*data[1]+0*data[2];

                double p=node1.getProbability(avg,null,false);
                double p2= node2.getProbability(avg2,null,false);
                System.out.println("p1: "+p);
                System.out.println("p2: "+p2);


            }

            private void printd(double[] avg) {
                for(double d: avg){
                    System.out.print(d+" ");
                }
                System.out.println();

            }
        });
    }
}
