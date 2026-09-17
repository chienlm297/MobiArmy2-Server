FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /app
COPY src/ src/
COPY lib/ lib/

RUN find src -name '*.java' -print > sources.txt \
    && mkdir -p build/classes \
    && javac --release 20 -encoding UTF-8 -cp 'lib/*' -d build/classes @sources.txt \
    && jar --create --file build/MobiArmy.jar \
        --main-class mobiarmy.MobiArmy -C build/classes .

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app
COPY --from=build /app/build/MobiArmy.jar ./MobiArmy.jar
COPY lib/ ./lib/
COPY res/ ./res/
COPY cache/ ./cache/

EXPOSE 8122

ENTRYPOINT ["java", "-cp", "MobiArmy.jar:lib/*", "mobiarmy.MobiArmy"]
