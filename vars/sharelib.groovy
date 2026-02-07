def call(Map config = [:], Closure body) {

    // ----- CONFIG -----
    String metricFile = config.get("metricFile", "metrics_${env.BUILD_ID}.csv")
    String interval   = config.get("interval", "5")
    String pidFile    = config.get("pidFile", "monitor.pid")
    int tailLines     = (config.get("tailLines", 10) as int)

    // ----- START MONITORING -----
    stage("Start Monitoring") {

        writeFile file: 'monitor.sh', text: '''
#!/bin/bash

LOG="$1"
INTERVAL="$2"

echo "timestamp,cpu_percent,mem_percent" > "$LOG"

cpu_usage() {
  local line
  line=$(top -bn1 | grep "Cpu(s)" || true)

  if [ -n "$line" ]; then
    awk 'match($0, /([0-9.]+)%us, *([0-9.]+)%sy/, a){printf "%.2f", a[1]+a[2]}' <<< "$line"
    return
  fi

  if command -v mpstat >/dev/null 2>&1; then
    mpstat 1 1 | awk "/Average:/ {printf \\"%.2f\\", 100-\\$NF}"
    return
  fi

  echo "NA"
}

mem_usage() {
  free | awk '/Mem:/ {printf "%.2f", ($3/$2)*100}'
}

while true; do
  TS=$(date +"%Y-%m-%d %H:%M:%S")
  CPU=$(cpu_usage)
  MEM=$(mem_usage)

  echo "$TS,$CPU,$MEM" >> "$LOG"
  sleep "$INTERVAL"
done
'''

        sh "chmod +x monitor.sh"
        sh "nohup ./monitor.sh '${metricFile}' '${interval}' > /dev/null 2>&1 & echo \$! > '${pidFile}'"
    }

    try {
        // ----- RUN USER PIPELINE -----
        body()
    }
    finally {

        // ----- STOP MONITORING -----
        sh "if [ -f '${pidFile}' ]; then kill \$(cat '${pidFile}') 2>/dev/null || true; fi"

        // ----- METADATA -----
        def startTime  = new Date(currentBuild.startTimeInMillis)
        long durationMs = currentBuild.duration > 0
                ? currentBuild.duration
                : (System.currentTimeMillis() - currentBuild.startTimeInMillis)

        def endTime = new Date(currentBuild.startTimeInMillis + durationMs)

        echo "==========================="
        echo "        JOB METADATA       "
        echo "==========================="
        echo "Job Name     : ${env.JOB_NAME}"
        echo "Build Number : ${env.BUILD_NUMBER}"
        echo "Agent Node   : ${env.NODE_NAME}"
        echo "Start Time   : ${startTime}"
        echo "End Time     : ${endTime}"
        echo "Duration     : ${durationMs/1000} sec"

        // ----- SUMMARY -----
        echo "==========================="
        echo "   CPU & MEMORY SUMMARY    "
        echo "==========================="

        sh """
if [ -f "${metricFile}" ]; then
  lines=\$(wc -l < "${metricFile}")
  echo "Samples collected: \$((lines-1))"

  awk -F, '
  NR>1 && \$2!="NA" && \$3!="NA" {
    cpu+=\$2; mem+=\$3
    if(mincpu==""||\$2<mincpu)mincpu=\$2
    if(maxcpu==""||\$2>maxcpu)maxcpu=\$2
    if(minmem==""||\$3<minmem)minmem=\$3
    if(maxmem==""||\$3>maxmem)maxmem=\$3
    n++
  }
  END {
    if(n>0){
      printf "CPU -> avg: %.2f%%, min: %.2f%%, max: %.2f%%\\n", cpu/n, mincpu, maxcpu
      printf "MEM -> avg: %.2f%%, min: %.2f%%, max: %.2f%%\\n", mem/n, minmem, maxmem
    } else {
      print "No valid numeric samples"
    }
  }' "${metricFile}"

  echo ""
  echo "Last ${tailLines} samples:"
  tail -n ${tailLines} "${metricFile}" || true
fi
"""

        // ----- ARCHIVE RESULTS -----
        archiveArtifacts artifacts: metricFile, fingerprint: true
    }
}

