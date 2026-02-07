def call(Map config = [:], Closure body) {

    String interval = config.get("interval", "5")

    stage("Start Monitoring") {

        writeFile file: 'monitor.sh', text: '''
#!/bin/bash

cpu_usage() {
  line=$(top -bn1 | grep "Cpu(s)" || true)
  if [ -n "$line" ]; then
    awk 'match($0,/([0-9.]+)%us, *([0-9.]+)%sy/,a){printf "%.2f",a[1]+a[2]}' <<< "$line"
  else
    echo "NA"
  fi
}

mem_usage() {
  free | awk '/Mem:/ {printf "%.2f", ($3/$2)*100}'
}

while true; do
  CPU=$(cpu_usage)
  MEM=$(mem_usage)
  echo "[MONITOR] CPU: ${CPU}% | MEM: ${MEM}%"
  sleep ''' + interval + '''
done
'''

        sh "chmod +x monitor.sh"
        sh 'nohup ./monitor.sh > monitor.log 2>&1 & echo $! > monitor.pid'
    }

    try {
        body()
    }
    finally {

        sh 'if [ -f monitor.pid ]; then kill $(cat monitor.pid) 2>/dev/null || true; fi'

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

        sh '''
CPU=$(top -bn1 | grep "Cpu(s)" | awk '{print $2+$4}')
MEM=$(free | awk '/Mem:/ {printf "%.2f", ($3/$2)*100}')

echo "==========================="
echo " FINAL RESOURCE USAGE "
echo "==========================="
echo "CPU Usage : ${CPU}%"
echo "MEM Usage : ${MEM}%"
'''
    }
}
