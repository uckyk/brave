package com.github.kristofa.brave;

import com.github.kristofa.brave.internal.V2SpanConverter;
import com.google.auto.value.AutoValue;
import com.twitter.zipkin.gen.Annotation;
import com.twitter.zipkin.gen.BinaryAnnotation;
import com.twitter.zipkin.gen.Endpoint;
import com.twitter.zipkin.gen.Span;
import java.util.List;
import zipkin2.reporter.Reporter;

import static com.github.kristofa.brave.internal.DefaultSpanCodec.toZipkin;

abstract class Recorder {
  abstract long currentTimeMicroseconds(Span span);

  abstract void name(Span span, String name);

  /** Used for local spans spans */
  abstract void start(Span span, long timestamp);

  abstract void annotate(Span span, long timestamp, String value);

  abstract void address(Span span, String key, Endpoint endpoint);

  abstract void tag(Span span, String key, String value);

  /** Implicitly calls flush */
  abstract void finish(Span span, long timestamp);

  /** Reports whatever is present even if unfinished. */
  abstract void flush(Span span);

  @AutoValue
  abstract static class Default extends Recorder {
    abstract Endpoint localEndpoint();

    abstract AnnotationSubmitter.Clock clock();

    abstract Reporter<zipkin2.Span> reporter();

    @Override
    long currentTimeMicroseconds(Span span) {
      return clock().currentTimeMicroseconds();
    }

    @Override
    void name(Span span, String name) {
      synchronized (span) {
        span.setName(name);
      }
    }

    @Override
    void start(Span span, long timestamp) {
      synchronized (span) {
        span.setTimestamp(timestamp);
      }
    }

    @Override
    void annotate(Span span, long timestamp, String value) {
      Annotation annotation = Annotation.create(timestamp, value, localEndpoint());
      synchronized (span) {
        span.addToAnnotations(annotation);
      }
    }

    @Override
    void address(Span span, String key, Endpoint endpoint) {
      BinaryAnnotation address = BinaryAnnotation.address(key, endpoint);
      synchronized (span) {
        span.addToBinary_annotations(address);
      }
    }

    @Override
    void tag(Span span, String key, String value) {
      BinaryAnnotation ba = BinaryAnnotation.create(key, value, localEndpoint());
      synchronized (span) {
        span.addToBinary_annotations(ba);
      }
    }

    @Override
    void finish(Span span, long timestamp) {
      synchronized (span) {
        Long startTimestamp = span.getTimestamp();
        if (startTimestamp != null) {
          span.setDuration(Math.max(1L, timestamp - startTimestamp));
        }
      }
      flush(span);
    }

    @Override
    void flush(Span span) {
      // In the RPC span model, the client owns the timestamp and duration of the span. If we
      // were propagated an id, we can assume that we shouldn't report timestamp or duration,
      // rather let the client do that. Worst case we were propagated an unreported ID and
      // Zipkin backfills timestamp and duration.
      synchronized (span) {
        if (span.isShared()) {
          for (int i = 0, length = span.getAnnotations().size(); i < length; i++) {
            if (span.getAnnotations().get(i).value.equals("sr")) {
              span.setTimestamp(null);
              break;
            }
          }
        }
      }
      List<zipkin2.Span> toReport = V2SpanConverter.fromSpan(toZipkin(span));
      for (int i = 0, length = toReport.size(); i < length; i++) {
        reporter().report(toReport.get(i));
      }
    }
  }
}