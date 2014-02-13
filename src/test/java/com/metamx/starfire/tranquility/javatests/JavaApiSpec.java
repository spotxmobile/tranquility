package com.metamx.starfire.tranquility.javatests;

import backtype.storm.task.IMetricsContext;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.metamx.common.Granularity;
import com.metamx.starfire.tranquility.beam.Beam;
import com.metamx.starfire.tranquility.beam.ClusteredBeamTuning;
import com.metamx.starfire.tranquility.druid.DruidBeams;
import com.metamx.starfire.tranquility.druid.DruidEnvironment;
import com.metamx.starfire.tranquility.druid.DruidLocation;
import com.metamx.starfire.tranquility.druid.DruidRollup;
import com.metamx.starfire.tranquility.storm.BeamBolt;
import com.metamx.starfire.tranquility.storm.BeamFactory;
import com.metamx.starfire.tranquility.typeclass.Timestamper;
import com.twitter.finagle.Service;
import io.druid.granularity.QueryGranularity;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.CountAggregatorFactory;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.apache.curator.test.TestingCluster;
import org.joda.time.DateTime;
import org.joda.time.Period;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.List;
import java.util.Map;

public class JavaApiSpec
{
  public static class MyBeamFactory implements BeamFactory<Map<String, Object>>
  {
    @Override
    public Beam<Map<String, Object>> makeBeam(Map<?, ?> conf, IMetricsContext metrics)
    {
      try {
        final TestingCluster cluster = new TestingCluster(1);
        final CuratorFramework curator = CuratorFrameworkFactory
            .builder()
            .connectString(cluster.getConnectString())
            .retryPolicy(new RetryOneTime(1000))
            .build();
        cluster.start();
        curator.start();

        final String dataSource = "hey";
        final List<String> dimensions = ImmutableList.of("column");
        final List<AggregatorFactory> aggregators = ImmutableList.<AggregatorFactory>of(
            new CountAggregatorFactory(
                "cnt"
            )
        );

        final DruidBeams.Builder<Map<String, Object>> builder = DruidBeams
            .builder(
                new Timestamper<Map<String, Object>>()
                {
                  @Override
                  public DateTime timestamp(Map<String, Object> theMap)
                  {
                    return new DateTime(theMap.get("timestamp"));
                  }
                }
            )
            .curator(curator)
            .discoveryPath("/test/discovery")
            .location(
                new DruidLocation(
                    new DruidEnvironment(
                        "druid:local:indexer",
                        "druid:local:firehose:%s"
                    ), dataSource
                )
            )
            .rollup(DruidRollup.create(dimensions, aggregators, QueryGranularity.MINUTE))
            .tuning(ClusteredBeamTuning.create(Granularity.HOUR, new Period("PT0M"), new Period("PT10M"), 1, 1));

        final Service<List<Map<String, Object>>, Integer> service = builder.buildJavaService();
        final Beam<Map<String, Object>> beam = builder.buildBeam();

        return beam;
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }
  }

  @Test
  public void testDruidBeamBoltConstruction() throws Exception
  {
    final BeamBolt<Map<String, Object>> beamBolt = new BeamBolt<>(new MyBeamFactory());

    // Ensure serializability
    final ObjectOutputStream objectOutputStream = new ObjectOutputStream(new ByteArrayOutputStream());
    objectOutputStream.writeObject(beamBolt);
  }
}
