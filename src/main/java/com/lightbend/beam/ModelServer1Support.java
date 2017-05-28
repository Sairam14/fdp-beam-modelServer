package com.lightbend.beam;

import com.lightbend.coders.ModelCoder;
import com.lightbend.model.*;
import org.apache.beam.sdk.state.StateSpec;
import org.apache.beam.sdk.state.StateSpecs;
import org.apache.beam.sdk.state.ValueState;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.SimpleFunction;
import org.apache.beam.sdk.values.KV;

import java.io.Serializable;

/**
 * Created by boris on 5/22/17.
 *
 * A set of supporting classes implementing methods used in MedelServer1 pipeline
 */

public class ModelServer1Support {

    // Intermediate model representation used for transporting models
    public static class ModelDescriptor implements Serializable {

        private String name;
        private String description;
        private Modeldescriptor.ModelDescriptor.ModelType modelType;
        private byte[] modelData;
        private String modelDataLocation;
        private String dataType;

        public ModelDescriptor(String name, String description, Modeldescriptor.ModelDescriptor.ModelType modelType,
                            byte[] dataContent, String modelDataLocation, String dataType){
            this.name = name;
            this.description = description;
            this.modelType = modelType;
            this.modelData = dataContent;
            this.modelDataLocation = modelDataLocation;
            this.dataType = dataType;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public Modeldescriptor.ModelDescriptor.ModelType getModelType() {
            return modelType;
        }

        public String getDataType() {
            return dataType;
        }

        public byte[] getModelData() {
            return modelData;
        }

        public String getModelDataLocation() {
            return modelDataLocation;
        }

        @Override
        public String toString() {
            return "ModelToServe{" +
                    "name='" + name + '\'' +
                    ", description='" + description + '\'' +
                    ", modelType=" + modelType +
                    ", dataType='" + dataType + '\'' +
                    '}';
        }
    }

    // Class combining data and model records to allow merging PCollections
    public static class DataWithModel implements Serializable {
        private Winerecord.WineRecord data;
        private ModelDescriptor model;

        public DataWithModel(Winerecord.WineRecord data){
            this.data = data;
            this.model = null;
        }
        public DataWithModel(ModelDescriptor model){
            this.data = null;
            this.model = model;
        }

        public Winerecord.WineRecord getData() {
            return data;
        }

        public ModelDescriptor getModel() {
            return model;
        }
    }

    // Converting Byte array to data record
    public static class ConvertDataRecordFunction extends DoFn<KV<byte[], byte[]>, KV<String,DataWithModel>> {

        @ProcessElement
        public void processElement(ProcessContext ctx){
            // Get current element
            KV<byte[],byte[]> input = ctx.element();
            try {
                // Unmarshall record
                Winerecord.WineRecord record = Winerecord.WineRecord.parseFrom(input.getValue());
                // Return it
                ctx.output(KV.of(record.getDataType(),new DataWithModel(record)));
            } catch (Throwable t) {
                // Oops
                System.out.println("Exception parsing input record" + new String(input.getValue()));
                t.printStackTrace();
            }
        }
    }

    // Converting Byte array to model descriptor
    public static class ConvertModelRecordFunction extends DoFn<KV<byte[], byte[]>, KV<String,DataWithModel>> {

        @ProcessElement
        public void processElement(ProcessContext ctx){
            // Get current element
            KV<byte[],byte[]> input = ctx.element();
            try {
                // Unmarshall record
                Modeldescriptor.ModelDescriptor model = Modeldescriptor.ModelDescriptor.parseFrom(input.getValue());
                // Return it
                if(model.getMessageContentCase().equals(Modeldescriptor.ModelDescriptor.MessageContentCase.DATA)){
                    ctx.output(KV.of(model.getDataType(), new DataWithModel(new ModelDescriptor(
                            model.getName(), model.getDescription(), model.getModeltype(),
                            model.getData().toByteArray(), null, model.getDataType()))));
                }
                else
                    System.out.println("Location based model is not yet supported");
            } catch (Throwable t) {
                // Oops
                System.out.println("Exception parsing input record" + new String(input.getValue()));
                t.printStackTrace();
            }
        }
    }

    // Based on https://beam.apache.org/blog/2017/02/13/stateful-processing.html
    public static class ScoringdFunction extends DoFn<KV<String,DataWithModel>, Double> {

        // Internal state
        @StateId("model")
        private final StateSpec<ValueState<Model>> modelSpec = StateSpecs.value(ModelCoder.of());


        @ProcessElement
        public void processElement(ProcessContext ctx, @StateId("model") ValueState<Model> modelState) {
            // Get current element
            KV<String,DataWithModel> input = ctx.element();
            // Check if we got the model
            ModelDescriptor descriptor = input.getValue().getModel();
            // Get current model
            Model model = modelState.read();
            if(descriptor != null) {
                // Process model - store it
                System.out.println("New scoring model " + descriptor);
                Model current = convertModel(descriptor);
                if (current != null) {
                    if (model != null)
                        model.cleanup();
                    // Create and store the model
                    modelState.write(current);
                }
            }
             // Process data
            else{
                if(model == null)
                    // No model currently
                    System.out.println("No model available - skipping");
                else{
                    // Score the model
                    long start = System.currentTimeMillis();
                    double quality = (double) model.score(input.getValue().getData());
                    long duration = System.currentTimeMillis() - start;
                    System.out.println("Calculated quality - " + quality + " in " + duration + "ms");
                    // Propagate result
                    ctx.output(quality);
                }
            }
        }

        private Model convertModel(ModelDescriptor descriptor){

            if(descriptor.getModelData() == null) {
                System.out.println("Location based model is not yet supported");
                return null;
            }
            try {
                Model current = null;
                switch (descriptor.getModelType()){
                    case PMML:
                        current = new PMMLModel(descriptor.getModelData());
                        break;
                    case TENSORFLOW:
                        current = new TensorModel(descriptor.getModelData());
                        break;
                    case UNRECOGNIZED:
                        System.out.println("Only PMML and Tensorflow models are currently supported");
                        break;
                }
                return current;
            } catch (Throwable t) {
                System.out.println("Failed to create model");
                t.printStackTrace();
                return null;
            }
        }
    }

    // Simple function to print content of collection
    public static class SimplePrinterFn<T> extends SimpleFunction<T, T> {
        @Override
        public T apply(T input) {
            // Print the variable
            System.out.println("Processing data " + input);
            // Propagate it
            return input;
        }
    }
}
