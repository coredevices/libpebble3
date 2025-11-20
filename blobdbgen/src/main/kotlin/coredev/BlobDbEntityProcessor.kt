@file:OptIn(KspExperimental::class)

package coredev

import com.google.devtools.ksp.KspExperimental
import com.google.devtools.ksp.getAnnotationsByType
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSVisitorVoid
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.BOOLEAN
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.INT
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec
import com.squareup.kotlinpoet.STRING
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo

class BlobDbEntityProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        BlobDbEntityProcessor(environment.logger, environment.codeGenerator)
}

class BlobDbEntityProcessor(
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
//    private val options: Map<String, String>
) : SymbolProcessor {
    override fun process(resolver: Resolver): List<KSAnnotated> {
        logger.warn("process")
        // Find all classes with the custom annotation
        val symbols = resolver.getSymbolsWithAnnotation(GenerateRoomEntity::class.qualifiedName!!)
        val ret = symbols.filterNot { it.validate() }.toList()
        symbols
            .filter { it is KSClassDeclaration && it.validate() }
            .forEach { it.accept(RoomEntityVisitor(), Unit) }
        return ret
    }

    // Visitor that iterates over properties
    inner class RoomEntityVisitor : KSVisitorVoid() {

        override fun visitClassDeclaration(classDeclaration: KSClassDeclaration, data: Unit) {
            logger.warn("visitClassDeclaration: $classDeclaration")
            if (classDeclaration.classKind != ClassKind.CLASS) {
                return
            }
            val annotation = classDeclaration.getAnnotationsByType(GenerateRoomEntity::class)
                .firstOrNull() ?: return
            val primaryKey = annotation.primaryKey
            val primaryKeyField = classDeclaration.getDeclaredProperties()
                .firstOrNull { it.simpleName.asString() == primaryKey }
            if (primaryKeyField == null) {
                throw IllegalStateException("couldn't find primary key: $primaryKey")
            }

            // Entity
            val entityClassName = classDeclaration.simpleName.asString() + "Entity"
            val entityTypeName = ClassName(classDeclaration.packageName.asString(), entityClassName)
            val entityBuilder = TypeSpec.classBuilder(entityClassName)
                .addAnnotation(
                    AnnotationSpec.builder(ClassName("androidx.room", "Entity"))
                        .addMember("primaryKeys = [\"$primaryKey\"]")
                        .build()
                )
                .addSuperinterface(
                    ClassName(
                        "io.rebble.libpebblecommon.database.dao",
                        "BlobDbRecord"
                    )
                )
                .addModifiers(KModifier.DATA)
            val entityConstructorBuilder = FunSpec.constructorBuilder()
            addField(
                entityBuilder, entityConstructorBuilder,
                "recordHashcode", INT, modifiers = listOf(
                    KModifier.OVERRIDE,
                )
            )
            addField(
                entityBuilder, entityConstructorBuilder,
                "deleted", BOOLEAN, modifiers = listOf(
                    KModifier.OVERRIDE,
                )
            )
            addField(
                entityBuilder, entityConstructorBuilder,
                "record", classDeclaration.toClassName(), annotations = listOf(
                    AnnotationSpec.builder(ClassName("androidx.room", "Embedded")).build(),
                ), modifiers = listOf(
                    KModifier.OVERRIDE,
                )
            )
            addField(
                entityBuilder, entityConstructorBuilder,
                "sync", BOOLEAN, annotations = listOf(
                    AnnotationSpec.builder(ClassName("androidx.room", "ColumnInfo"))
                        .addMember("defaultValue = %S", "1")
                        .build(),
                )
            ) {
                defaultValue("%L", true)
            }
            entityBuilder.primaryConstructor(entityConstructorBuilder.build())
            logger.warn("creating $entityClassName")

            // Sync entity
            val syncEntityClassName = classDeclaration.simpleName.asString() + "SyncEntity"
            val syncEntityTypeName = ClassName(classDeclaration.packageName.asString(), syncEntityClassName)
            val syncEntityBuilder = TypeSpec.classBuilder(syncEntityClassName)
                .addAnnotation(
                    AnnotationSpec.builder(ClassName("androidx.room", "Entity"))
                        .addMember("""primaryKeys = ["recordId", "transport"]""")
                        .addMember(
                            """
                            foreignKeys = [
                                ForeignKey(
                                    entity = $entityClassName::class,
                                    parentColumns = ["$primaryKey"],
                                    childColumns = ["recordId"],
                                    onDelete = ForeignKey.CASCADE,
                                    onUpdate = ForeignKey.CASCADE,
                                )
                            ]
                            """.trimIndent()
                        )
                        .build()
                )
                .addModifiers(KModifier.DATA)
            val syncEntityConstructorBuilder = FunSpec.constructorBuilder()
            addField(
                syncEntityBuilder,
                syncEntityConstructorBuilder,
                "recordId",
                primaryKeyField.type.resolve().toClassName(),
            )
            addField(
                syncEntityBuilder,
                syncEntityConstructorBuilder,
                "transport",
                STRING,
            )
            addField(
                syncEntityBuilder,
                syncEntityConstructorBuilder,
                "watchSynchHashcode",
                INT,
            )
            syncEntityBuilder.primaryConstructor(syncEntityConstructorBuilder.build())

            // Dao
            val daoClassName = classDeclaration.simpleName.asString() + "Dao"
            val daoBuilder = TypeSpec.interfaceBuilder(daoClassName)
                .addAnnotation(ClassName("androidx.room", "Dao"))
                .addSuperinterface(ClassName("io.rebble.libpebblecommon.database.dao", "BlobDbDao").parameterizedBy(entityTypeName))
            val listTypeOfBlobDbRecord = List::class.asClassName().parameterizedBy(entityTypeName)

            val syncSelectClause = StringBuilder()
            syncSelectClause.append("e.deleted = 0\n")
            syncSelectClause.append("AND e.sync = 1\n")
            if (annotation.windowBeforeSecs > -1) {
                val ms = annotation.windowBeforeSecs * 1000
                syncSelectClause.append("AND :timestampMs >= (e.timestamp - $ms)\n")
            }
            if (annotation.windowAfterSecs > -1) {
                val ms = annotation.windowAfterSecs * 1000
                syncSelectClause.append("AND :timestampMs <= (e.timestamp + $ms)\n")
            }
            if (annotation.windowBeforeSecs == -1L && annotation.windowAfterSecs == -1L) {
                // Because room fails to compile if we don't use the :timestampMs variable in the query
                syncSelectClause.append("AND :timestampMs = :timestampMs\n")
            }

            val onlyInsertAfterClause = if (annotation.onlyInsertAfter) {
                "AND e.timestamp > :insertOnlyAfterMs\n"
            } else {
                // Because room fails to compile if we don't use the :timestampMs variable in the query
                "AND :insertOnlyAfterMs = :insertOnlyAfterMs\n"
            }
            val flowOfListOfBlobDbRecord =
                ClassName("kotlinx.coroutines.flow", "Flow").parameterizedBy(listTypeOfBlobDbRecord)
            daoBuilder.addFunction(
                FunSpec.builder("dirtyRecordsForWatchInsert")
                    .addModifiers(KModifier.OVERRIDE, KModifier.ABSTRACT)
                    .addParameter("identifier", STRING)
                    .addParameter("timestampMs", LONG)
                    .addParameter("insertOnlyAfterMs", LONG)
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                            .addMember(
                                "value = %S",
                                """
                            SELECT e.*
                            FROM $entityClassName e
                            WHERE 
                                ($syncSelectClause)
                                $onlyInsertAfterClause
                                AND
                                NOT EXISTS (
                                    SELECT 1
                                    FROM $syncEntityClassName s
                                    WHERE s.recordId = e.$primaryKey
                                    AND s.transport = :identifier
                                    AND s.watchSynchHashcode = e.recordHashcode
                                )
                            """.trimIndent()
                            )
                            .build()
                    )
                    .returns(flowOfListOfBlobDbRecord)
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("dirtyRecordsForWatchDelete")
                    .addParameter("identifier", STRING)
                    .addParameter("timestampMs", LONG)
                    .apply {
                        if (annotation.sendDeletions) {
                            addModifiers(KModifier.OVERRIDE, KModifier.ABSTRACT)
                            addAnnotation(
                                AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                                    .addMember(
                                        "value = %S",
                                        """
                            SELECT e.*
                            FROM $entityClassName e
                            WHERE 
                                NOT ($syncSelectClause)
                                AND
                                EXISTS (
                                    SELECT 1
                                    FROM $syncEntityClassName s
                                    WHERE s.recordId = e.$primaryKey
                                    AND s.transport = :identifier
                                )
                            """.trimIndent()
                                    )
                                    .build()
                            )
                        } else {
                            // Deletions are always empty
                            addModifiers(KModifier.OVERRIDE)
                            addStatement("return flowOf(emptyList())")
                        }

                    }
                    .returns(flowOfListOfBlobDbRecord)
                    .build()
            )

            val purgeSelectClause = if (annotation.windowAfterSecs > -1) {
                val ms = annotation.windowAfterSecs * 1000
                "OR :timestampMs > (e.timestamp + $ms)\n"
            } else {
                // Because room fails to compile if we don't use the :timestampMs variable in the query
                "OR :timestampMs != :timestampMs\n"
            }
            val andNotsyncedToWatchClause = if (annotation.sendDeletions) {
                """
                    AND
                    NOT EXISTS (
                        SELECT 1
                        FROM $syncEntityClassName s
                        WHERE s.recordId = e.$primaryKey
                    )
                """.trimIndent()
            } else {
                ""
            }
            daoBuilder.addFunction(
                FunSpec.builder("deleteStaleRecords")
                    .addModifiers(KModifier.OVERRIDE, KModifier.ABSTRACT, KModifier.SUSPEND)
                    .addParameter("timestampMs", LONG)
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                            .addMember(
                                "value = %S",
                                """
                            DELETE
                            FROM $entityClassName as e
                            WHERE 
                                (
                                    e.deleted = 1
                                    $purgeSelectClause
                                )
                                $andNotsyncedToWatchClause
                            """.trimIndent()
                            )
                            .build()
                    )
                    .build()
            )

            daoBuilder.addFunction(
                FunSpec.builder("markSyncedToWatch")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addParameter("identifier", STRING)
                    .addParameter("item", entityTypeName)
                    .addParameter("hashcode", INT)
                    .addStatement("markSyncedToWatch($syncEntityClassName(item.record.$primaryKey, identifier, hashcode))")
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("markSyncedToWatch")
                    .addModifiers(KModifier.ABSTRACT, KModifier.SUSPEND)
                    .addParameter("syncRecord", syncEntityTypeName)
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Upsert"))
                            .build()
                    )
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("markDeletedFromWatch")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND)
                    .addParameter("identifier", STRING)
                    .addParameter("item", entityTypeName)
                    .addParameter("hashcode", INT)
                    .addStatement("markDeletedFromWatch($syncEntityClassName(item.record.$primaryKey, identifier, hashcode))")
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("markDeletedFromWatch")
                    .addModifiers(KModifier.ABSTRACT, KModifier.SUSPEND)
                    .addParameter("syncRecord", syncEntityTypeName)
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Delete"))
                            .build()
                    )
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("existsOnWatch")
                    .addModifiers(KModifier.OVERRIDE)
                    .addParameter("identifier", STRING)
                    .addParameter("item", entityTypeName)
                    .returns(ClassName("kotlinx.coroutines.flow", "Flow").parameterizedBy(BOOLEAN))
                    .addStatement("return existsOnWatch(identifier, item.record.$primaryKey)")
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("existsOnWatch")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter("identifier", STRING)
                    .addParameter("primaryKey", primaryKeyField.type.resolve().toClassName())
                    .returns(ClassName("kotlinx.coroutines.flow", "Flow").parameterizedBy(BOOLEAN))
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                            .addMember(
                                "value = %S",
                                """
                            SELECT EXISTS (
                                SELECT 1
                                FROM $syncEntityClassName s
                                WHERE s.recordId = :primaryKey
                                AND s.transport = :identifier
                            )
                            """.trimIndent()
                            ).build()
                    )
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("insertOrReplace")
                    .addModifiers(KModifier.SUSPEND)
                    .addParameter("item", classDeclaration.toClassName())
                    .addStatement("""
                        val hashcode = item.recordHashCode()
                        val deleted = false
                        insertOrReplace($entityClassName(hashcode, deleted, item))
                        """.trimIndent())
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("insertOrReplace")
                    .addModifiers(KModifier.SUSPEND)
                    .addParameter("items", List::class.asClassName().parameterizedBy(classDeclaration.toClassName()))
                    .addStatement("""
                        val mapped = items.map {
                            val hashcode = it.recordHashCode()
                            val deleted = false
                            $entityClassName(hashcode, deleted, it)
                        }
                        insertOrReplaceAll(mapped)
                        """.trimIndent())
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("markForDeletion")
                    .addModifiers(KModifier.ABSTRACT, KModifier.SUSPEND)
                    .addParameter(primaryKey, primaryKeyField.type.resolve().toClassName())
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                            .addMember(
                                "value = %S",
                                """
                            UPDATE
                            $entityClassName
                            SET deleted = 1
                            WHERE $primaryKey = :$primaryKey
                            """.trimIndent()
                            )
                            .build()
                    )
                    .build()
            )
            val pluralPkFieldName = "${primaryKey}s"
            daoBuilder.addFunction(
                FunSpec.builder("markAllForDeletion")
                    .addModifiers(KModifier.ABSTRACT, KModifier.SUSPEND)
                    .addParameter(pluralPkFieldName, List::class.asClassName().parameterizedBy(primaryKeyField.type.resolve().toClassName()))
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                            .addMember(
                                "value = %S",
                                """
                            UPDATE
                            $entityClassName
                            SET deleted = 1
                            WHERE $primaryKey IN (:$pluralPkFieldName)
                            """.trimIndent()
                            )
                            .build()
                    )
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("databaseId")
                    .addModifiers(KModifier.OVERRIDE)
                    .returns(BlobDatabase::class)
                    .addStatement("return coredev.BlobDatabase.${annotation.databaseId}")
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("markAllDeletedFromWatch")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND, KModifier.ABSTRACT)
                    .addParameter("identifier", STRING)
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                            .addMember(
                                "value = %S",
                                """
                            DELETE
                            FROM $syncEntityClassName
                            WHERE transport = :identifier
                            """.trimIndent()
                            )
                            .build()
                    )
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("deleteSyncRecordsForDevicesWhichDontExist")
                    .addModifiers(KModifier.OVERRIDE, KModifier.SUSPEND, KModifier.ABSTRACT)
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                            .addMember(
                                "value = %S",
                                """
                            DELETE
                            FROM $syncEntityClassName as s
                            WHERE NOT EXISTS
                            (SELECT 1 FROM KnownWatchItem as k WHERE k.transportIdentifier = s.transport)
                            """.trimIndent()
                            )
                            .build()
                    )
                    .build()
            )
            daoBuilder.addFunction(
                FunSpec.builder("getEntry")
                    .addModifiers(KModifier.SUSPEND, KModifier.ABSTRACT)
                    .addParameter(primaryKey, primaryKeyField.type.resolve().toClassName())
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                            .addMember(
                                "value = %S",
                                """
                            SELECT *
                            FROM $entityClassName
                            WHERE $primaryKey = :$primaryKey
                            AND deleted = 0
                            """.trimIndent()
                            )
                            .build()
                    )
                    .returns(classDeclaration.toClassName().copy(nullable = true))
                    .build()
            )
            val flowOfNullableBlobDbRecord =
                ClassName("kotlinx.coroutines.flow", "Flow").parameterizedBy(classDeclaration.toClassName().copy(nullable = true))
            daoBuilder.addFunction(
                FunSpec.builder("getEntryFlow")
                    .addModifiers(KModifier.ABSTRACT)
                    .addParameter(primaryKey, primaryKeyField.type.resolve().toClassName())
                    .addAnnotation(
                        AnnotationSpec.builder(ClassName("androidx.room", "Query"))
                            .addMember(
                                "value = %S",
                                """
                            SELECT *
                            FROM $entityClassName
                            WHERE $primaryKey = :$primaryKey
                            AND deleted = 0
                            """.trimIndent()
                            )
                            .build()
                    )
                    .returns(flowOfNullableBlobDbRecord)
                    .build()
            )
            val file = FileSpec.builder(classDeclaration.packageName.asString(), entityClassName)
                .addType(entityBuilder.build())
                .addType(syncEntityBuilder.build())
                .addType(daoBuilder.build())
                .addImport("androidx.room", "ForeignKey")
                .addImport("kotlinx.coroutines.flow", "flowOf")
                .build()
            file.writeTo(codeGenerator, true)
        }
    }
}

private fun addField(
    entityBuilder: TypeSpec.Builder,
    constructorBuilder: FunSpec.Builder,
    name: String,
    type: TypeName,
    annotations: List<AnnotationSpec> = emptyList(),
    modifiers: List<KModifier> = emptyList(),
    block: ParameterSpec.Builder.() -> Unit = {},
) {
    entityBuilder.addProperty(
        PropertySpec.builder(name, type)
            .initializer(name)
            .apply {
                addAnnotations(annotations)
                addModifiers(modifiers)
            }
            .build())
    constructorBuilder.addParameter(
        ParameterSpec.builder(name = name, type = type)
            .apply { block() }
            .build()
    )
}
