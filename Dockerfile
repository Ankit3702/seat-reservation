FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /srv
COPY --from=build /build/target/seat-reservation-1.0.0.jar app.jar
ENV DB_PATH=/tmp/seats PORT=8000
EXPOSE 8000
# Small heap + SerialGC: free tier is 512MB total; 200 Tomcat threads at 1MB
# stacks each plus a 512m heap OOM-kills the container under burst.
CMD ["sh","-c","java -Xms128m -Xmx300m -XX:MaxMetaspaceSize=160m -XX:+UseSerialGC -jar app.jar"]
