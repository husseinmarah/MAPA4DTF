"""
Visual Components Dynamic Component Creator - Direct Execution
==============================================================================

"""

from vcScript import *
import os


def convert_to_camel_case(name):
    
    if not name:
        return name
    
    # Handle special cases for specific property names
    name_mapping = {
        "InputConveyorQuantity": "inputconveyorQuantity",
        "ProductType": "productType", 
        "CloneTimeInterval": "clonetimeInterval",
        "CloneCount": "cloneCount",
        "Produced": "produced",
        "Target": "target",
        "Stop": "stop",
        "CarryingProduct": "carryingProduct",
        "CarriedProduct": "carriedProduct",
        "BatteryLevel": "batteryLevel",
        "Location": "location",
        "NextLocation": "nextLocation",
        "Priority": "priority",
        "MaxSpeed": "maxSpeed",
        "InitialPositions": "initialPositions"
    }
    
    # Return mapped name if it exists, otherwise convert to camelCase
    if name in name_mapping:
        return name_mapping[name]
    
    # General PascalCase to camelCase conversion
    if len(name) > 1 and name[0].isupper() and name[1].isupper():
        return name[0].lower() + name[1:]
    elif len(name) > 0 and name[0].isupper():
        return name[0].lower() + name[1:]
    return name

def is_original_format(name):
    """Check if a property name is already in the original format (PascalCase) that scripts expect."""
    if not name:
        return False
    
    # Remove {} placeholder for checking
    base_name = name.replace("{}", "") if "{}" in name else name
    
    # Check if the base name starts with uppercase (PascalCase)
    if len(base_name) > 0 and base_name[0].isupper():
        return True
    
    return False

def convert_from_camel_case_to_original(name):
    """Convert camelCase names back to original names that scripts expect."""
    if not name:
        return name
    
    # If the name is already in original format, no translation needed
    if is_original_format(name):
        return name
    
    # Handle templates with {} placeholders - remove {} for mapping lookup
    base_name = name.replace("{}", "") if "{}" in name else name
    has_placeholder = "{}" in name
    
    # Reverse mapping: camelCase -> original names
    reverse_mapping = {
        "inputconveyorQuantity": "InputConveyorQuantity",
        "productType": "ProductType", 
        "clonetimeInterval": "CloneTimeInterval",
        "cloneCount": "CloneCount",
        "produced": "Produced",
        "target": "Target",
        "stop": "Stop",
        "carryingProduct": "CarryingProduct",
        "carriedProduct": "CarriedProduct",
        "batteryLevel": "BatteryLevel",
        "location": "Location",
        "nextLocation": "NextLocation",
        "priority": "Priority",
        "maxSpeed": "MaxSpeed",
        "initialPositions": "InitialPositions",
        "robotQuantity": "RobotQuantity",
        "pathwayProperties": "pathwayProperties",
        "outputconveyorProperties": "outputconveyorProperties",
        "inputconveyorProperties": "inputconveyorProperties",
        "idleProperties": "idleProperties"
    }
    
    # Look up the base name (without {}) in the mapping
    if base_name in reverse_mapping:
        original_base = reverse_mapping[base_name]
        # If original had {}, add it back
        if has_placeholder:
            return original_base + "{}"
        else:
            return original_base
    
    # If no mapping found, return the original name unchanged
    return name

def convert_boolean_value(value):
    """Convert Python boolean values for metamodel compatibility."""
    if isinstance(value, bool):
        # For metamodel compatibility, convert Python booleans to lowercase strings
        # but handle them properly in the Visual Components system
        return value  # Keep as Python boolean for VC, metamodel will handle conversion
    return value

def create_metamodel_compatible_properties(component, config):
    """Create additional properties with camelCase names for metamodel compatibility."""
    
    # Create metamodel-compatible properties for regular properties
    if "properties" in config:
        for prop_config in config["properties"]:
            mapped_name = prop_config["name"]
            original_name = convert_from_camel_case_to_original(mapped_name)
            
            # Only create metamodel properties if the original name is different from the mapped name
            # This means translation was needed
            if mapped_name != original_name:
                camel_case_name = convert_to_camel_case(original_name)
                
                # If names are different, create the camelCase name property too
                if original_name != camel_case_name:
                    if not component.getProperty(camel_case_name):
                        type_map = {"string": VC_STRING, "number": VC_INTEGER, "integer": VC_INTEGER, "real": VC_REAL, "boolean": VC_BOOLEAN}
                        camel_case_prop = component.createProperty(type_map.get(prop_config["type"], VC_STRING), camel_case_name)
                        print("Created metamodel-compatible property: {}".format(camel_case_name))
                        
                        # Link it to the original property
                        original_prop = component.getProperty(original_name)
                        if original_prop:
                            camel_case_prop.Value = original_prop.Value
    
    # Create metamodel-compatible properties for numbered properties
    if "numbered_properties" in config:
        num_sets = config.get("property_sets", 10)
        for i in range(1, num_sets + 1):
            for prop_config in config["numbered_properties"]:
                mapped_template = prop_config["name_template"]
                original_template = convert_from_camel_case_to_original(mapped_template)
                
                # Only create metamodel properties if the original template is different from the mapped template
                # This means translation was needed
                if mapped_template != original_template:
                    # Calculate both names
                    if "{}" in original_template:
                        original_name = original_template.format(i)
                        base_name = original_template.replace("{}", "")
                        camel_case_name = convert_to_camel_case(base_name) + str(i)
                    else:
                        original_name = original_template + str(i)
                        camel_case_name = convert_to_camel_case(original_template) + str(i)
                    
                    # If names are different, create the camelCase name property too
                    if original_name != camel_case_name:
                        if not component.getProperty(camel_case_name):
                            type_map = {"string": VC_STRING, "number": VC_INTEGER, "integer": VC_INTEGER, "real": VC_REAL, "boolean": VC_BOOLEAN}
                            camel_case_prop = component.createProperty(type_map.get(prop_config["type"], VC_STRING), camel_case_name)
                            print("Created metamodel-compatible property: {}".format(camel_case_name))
                            
                            # Link it to the original property
                            original_prop = component.getProperty(original_name)
                            if original_prop:
                                camel_case_prop.Value = original_prop.Value

def create_component(app, config):
    """Create a single component based on configuration."""
    
    component_name = config["name"]
    component_folder = config["folder"]
    
    print("Creating " + component_name + "...")
    
    # Build possible file paths
    possible_paths = []
    
    for version in VISUAL_COMPONENTS_VERSIONS:
        # TEMPLATE: Replace hardcoded base path with path from metamodel SystemConfiguration.visualComponentsPath attribute
        base_path = VISUAL_COMPONENTS_PATH + version + "\\Models\\Components\\Visual Components\\"
        possible_paths.append(base_path + component_folder + "\\" + component_name + ".vcmx")
    
    # Find the component file
    vcmx_path = None
    for path in possible_paths:
        if os.path.exists(path):
            vcmx_path = path
            break
    
    if not vcmx_path:
        print(component_name + " .vcmx file not found")
        return None
    
    try:
        component = app.load("file:///" + vcmx_path)
        
        if component:
            # Make template invisible immediately
            component.Visible = False
            
            # Use layout_name if specified, otherwise use default naming
            if "layout_name" in config:
                component.Name = config["layout_name"]
            else:
                component.Name = "_Template_" + component_name.replace(" ", "_")
            
            # Create Vehicle behavior for Mobile Robot Resource
            if component_name == "Mobile Robot Resource":
                if not component.findBehaviour("Vehicle"):
                    vehicle = component.createBehaviour(VC_VEHICLE, "Vehicle")
                    # TEMPLATE: Replace hardcoded vehicle properties with values from metamodel Robot.acceleration, Robot.deceleration, Robot.maxSpeed, Robot.interpolation attributes
                    # Configure vehicle properties
                    vehicle.Acceleration = 300.0
                    vehicle.Deceleration = 300.0
                    vehicle.MaxSpeed = 800.0
                    vehicle.Interpolation = 0.15
            
            # Create properties with ORIGINAL names for script compatibility
            if "properties" in config:
                for prop_config in config["properties"]:
                    
                    mapped_name = prop_config["name"]
                    original_name = convert_from_camel_case_to_original(mapped_name)
                    
                    if not component.getProperty(original_name):
                       
                        type_map = {"string": VC_STRING, "number": VC_INTEGER, "integer": VC_INTEGER, "real": VC_REAL, "boolean": VC_BOOLEAN}
                        new_prop = component.createProperty(type_map.get(prop_config["type"], VC_STRING), original_name)
                        print("Created property: {original_name} (type: {prop_config['type']}) - mapped from {mapped_name}")
                        
                        if "default" in prop_config:
                            new_prop.Value = convert_boolean_value(prop_config["default"])
            
            # Create numbered properties with ORIGINAL names for script compatibility
            if "numbered_properties" in config:
                num_sets = config.get("property_sets", 10)
                for i in range(1, num_sets + 1):
                    for prop_config in config["numbered_properties"]:
                        # Convert the mapped template name from test.py back to original template that scripts expect
                        mapped_template = prop_config["name_template"]
                        original_template = convert_from_camel_case_to_original(mapped_template)
                        
                        # Create properties with ORIGINAL names that scripts expect
                        if "{}" in original_template:
                            # Template has placeholder - replace {} with number
                            prop_name = original_template.format(i)
                        else:
                            # Template has no placeholder - just add number
                            prop_name = original_template + str(i)
                        
                        if not component.getProperty(prop_name):
                            # Map types for metamodel compatibility: integer/real -> number
                            type_map = {"string": VC_STRING, "number": VC_INTEGER, "integer": VC_INTEGER, "real": VC_REAL, "boolean": VC_BOOLEAN}
                            new_prop = component.createProperty(type_map.get(prop_config["type"], VC_STRING), prop_name)
                            print("Created property: {} (type: {}) - mapped from {}".format(prop_name, prop_config['type'], mapped_template))
                            
                            if "default" in prop_config:
                                if original_template == "Priority{}" and prop_config["type"] == "integer":
                                    new_prop.Value = i  # Set priority to robot index
                                else:
                                    new_prop.Value = convert_boolean_value(prop_config["default"])
                        else:
                            print("Property already exists: {}".format(prop_name))
            
            # Create additional properties with camelCase names for metamodel compatibility
            create_metamodel_compatible_properties(component, config)
            
            # Add script
            if "script" in config:
                script_behavior = component.createBehaviour(VC_PYTHONSCRIPT, "ComponentScript")
                script_prop = script_behavior.getProperty("Script")
                if script_prop:
                    script_prop.Value = config["script"]
            
            return component
            
    except Exception as e:
        print("Error: " + str(e))
        return None


# Pathway Area script
PathwayArea = '''from vcScript import *
import vcMatrix as mat 

comp = getComponent()
app = getApplication()
pathways = []

def OnStart():
    # TEMPLATE: Replace hardcoded property name 'pathwayProperties' with PathwayArea.opcuaPropertyName attribute from metamodel
    pathway_prop = comp.getProperty('pathwayProperties')
    if pathway_prop:
        pathway_prop.OnChanged = create_pathways

def create_pathways(prop):
    global pathways
    
    if not prop.Value or prop.Value == "[]":
        return
    
    # Clean up existing
    for p in pathways:
        try: p.delete()
        except: pass
    pathways = []
    
    # Parse and create
    try:
        properties = eval(prop.Value)
    except:
        return
    
    for props in properties:
        new_pathway = comp.clone()
        if new_pathway:
            new_pathway.Name = props['Name']
            new_pathway.Visible = True  # Make clone visible
            
            mtx = mat.new()
            mtx.rotateAbsZ(props.get('Rz', 0))
            mtx.translateAbs(props.get('X', 0), props.get('Y', 0), props.get('Z', 0))
            new_pathway.PositionMatrix = mtx
            
            if 'AreaLength' in props:
                new_pathway.AreaLength = props['AreaLength']
            if 'AreaWidth' in props:
                new_pathway.AreaWidth = props['AreaWidth']
            
            pathways.append(new_pathway)
    
    app.render()

def OnRun():
    # TEMPLATE: Replace hardcoded wait time '50' with PathwayArea.opcuaWaitCycles attribute from metamodel
    # Wait for OPC-UA data
    for i in range(50):  # 5 seconds max
        # TEMPLATE: Replace hardcoded property name 'PathwayProperties' with PathwayArea.opcuaPropertyName attribute from metamodel
        pathway_prop = comp.getProperty('pathwayProperties')
        if pathway_prop and pathway_prop.Value and pathway_prop.Value != "[]":
            create_pathways(pathway_prop)
            break
        delay(0.1)

def OnReset():
    global pathways
    for p in pathways:
        try: p.delete()
        except: pass
    pathways = []
'''


# Conveyor script
OutputConveyor = '''from vcScript import *
import vcMatrix as mat

comp = getComponent()
app = getApplication()
output_conveyors = []

def OnStart():
    # TEMPLATE: Replace hardcoded property name 'output_conveyor_Properties' with OutputConveyor.opcuaPropertyName attribute from metamodel
    conveyor_prop = comp.getProperty('outputconveyorProperties')
    if conveyor_prop:
        conveyor_prop.OnChanged = create_conveyors

def create_conveyors(prop):
    global output_conveyors
    
    if not prop.Value or prop.Value == "[]":
        return
    
    # Clean up existing
    for c in output_conveyors:
        try: c.delete()
        except: pass
    output_conveyors = []
    
    # Parse and create
    try:
        output_conveyor_properties = eval(prop.Value)
    except:
        return
    
    for props in output_conveyor_properties:
        new_conveyor = comp.clone()
        if new_conveyor:
            new_conveyor.Name = props['Name']
            new_conveyor.Visible = True  # Make clone visible
            
            # Create a new matrix
            mtx = mat.new()
            
            # First, apply rotation around Z-axis
            mtx.rotateAbsZ(props.get('Rz', 0))
            
            # Then, apply translation
            mtx.translateAbs(props.get('X', 0), props.get('Y', 0), props.get('Z', 0))
            
            # Set the PositionMatrix of the cloned conveyor
            new_conveyor.PositionMatrix = mtx
            
            output_conveyors.append(new_conveyor)
    
    app.render()

def OnRun():
    # TEMPLATE: Replace hardcoded delay time '1' with OutputConveyor.opcuaDelayTime attribute from metamodel
    # Wait for OPC-UA data
    delay(1)
    
    # TEMPLATE: Replace hardcoded property name 'output_conveyor_Properties' with OutputConveyor.opcuaPropertyName attribute from metamodel
    # Read the properties from OPC-UA
    conveyor_prop = comp.getProperty('outputconveyorProperties')
    if conveyor_prop and conveyor_prop.Value and conveyor_prop.Value != "[]":
        create_conveyors(conveyor_prop)

def OnReset():
    global output_conveyors
    for output_conveyor in output_conveyors:
        try:
            output_conveyor.delete()
        except:
            pass
    output_conveyors = []
'''


# Input Conveyor script
InputConveyor = '''from vcScript import *
import vcMatrix

app = getApplication()
sim = getSimulation()
comp = getComponent()

# Global lists to keep track of cloned conveyors and components
cloned_conveyors = []
cloned_components = []

# TEMPLATE: Replace hardcoded MAX_CONVEYORS '10' with InputConveyor.maxInstances attribute from metamodel
# Maximum supported conveyors (should match the number of pre-created properties)
MAX_CONVEYORS = 10

def OnStart():
    # TEMPLATE: Replace hardcoded property name 'Input_Conveyor_Location' with InputConveyor.locationPropertyName attribute from metamodel
    # Setup property change handler
    input_location_prop = comp.getProperty('inputconveyorProperties')
    if input_location_prop:
        input_location_prop.OnChanged = lambda prop: clone_conveyors()

def clone_conveyors():
    global cloned_conveyors

    # Clean up existing clones
    for conveyor in cloned_conveyors:
        try: conveyor.delete()
        except: pass
    cloned_conveyors = []

    # TEMPLATE: Replace hardcoded property name 'Input_Conveyor_Location' with InputConveyor.locationPropertyName attribute from metamodel
    # Get the Input_Conveyor_Location property
    location_prop = comp.getProperty('inputconveyorProperties')
    if not location_prop or not location_prop.Value or location_prop.Value == "[]":
        return
    
    # Parse location data using eval (like other components)
    try:
        conveyor_locations = eval(location_prop.Value)
    except:
        print("Error parsing Input_Conveyor_Location data")
        return
    
    # Determine quantity from location data
    conveyor_quantity = min(len(conveyor_locations), MAX_CONVEYORS)
    
    # TEMPLATE: Replace hardcoded property name 'InputConveyorQuantity' with InputConveyor.quantityPropertyName attribute from metamodel
    # Update InputConveyorQuantity
    quantity_prop = comp.getProperty('InputConveyorQuantity')
    if quantity_prop:
        quantity_prop.Value = conveyor_quantity

    # Get property values for each conveyor from location data
    product_types = []
    clone_time_intervals = []
    clone_counts = []
    produced_props = []
    
    for i in range(conveyor_quantity):
        location = conveyor_locations[i]
        
        # TEMPLATE: Replace hardcoded fallback values 'Component{}'.format(i + 1) and 160.0 with InputConveyor.defaultProductType and InputConveyor.defaultProductionInterval attributes from metamodel
        # ProductType from location data (fallback to default if not specified)
        product_type = location.get('ProductType', 'Component{}'.format(i + 1))
        product_types.append(product_type)

        # ProductionInterval from location data (fallback to default if not specified)
        production_interval = location.get('ProductionInterval', 160.0)
        # Convert to float if it's a string
        if isinstance(production_interval, str):
            try:
                production_interval = float(production_interval)
            except ValueError:
                # TEMPLATE: Replace hardcoded fallback value '160.0' with InputConveyor.defaultProductionInterval attribute from metamodel
                production_interval = 160.0
        clone_time_intervals.append(production_interval)

        # CloneCount from template properties (for tracking purposes)
        prop = comp.getProperty('CloneCount{}'.format(i + 1))
        clone_counts.append(prop)

        # Produced from template properties (for OPC-UA communication)
        prop = comp.getProperty('Produced{}'.format(i + 1))
        produced_props.append(prop)

    # Clone and position conveyors
    for i in range(conveyor_quantity):
        conveyor = comp.clone()
        
        # Use 'Name' from location data if available, otherwise use default naming
        location = conveyor_locations[i]
        if 'Name' in location and location['Name']:
            conveyor.Name = location['Name']
        else:
            conveyor.Name = 'InputConveyor #{}'.format(i + 1)
            
        conveyor.Visible = True
        cloned_conveyors.append(conveyor)
        
        # Position the conveyor
        x = location.get('X', 0)
        y = location.get('Y', 0)
        rz = location.get('Rz', 0)
        
        mtx = vcMatrix.new()
        mtx.rotateAbsZ(rz)
        mtx.translateAbs(x, y, 0)
        conveyor.PositionMatrix = mtx

        # Set properties
        set_conveyor_properties(conveyor, i + 1, product_types[i], clone_time_intervals[i], clone_counts[i], produced_props[i])
    
    app.render()

def set_conveyor_properties(conveyor, index, product_type, clone_time_interval, clone_count_prop, produced_prop):
    # ProductType (from location data)
    prop = conveyor.getProperty('ProductType')
    if not prop:
        prop = conveyor.createProperty(VC_STRING, 'ProductType')
    prop.Value = product_type

    # CloneTimeInterval (from location data as ProductionInterval)
    prop = conveyor.getProperty('CloneTimeInterval')
    if not prop:
        prop = conveyor.createProperty(VC_REAL, 'CloneTimeInterval')
    prop.Value = clone_time_interval

    # Produced (linked to template property for OPC-UA communication)
    prop = conveyor.getProperty('Produced')
    if not prop:
        prop = conveyor.createProperty(VC_BOOLEAN, 'Produced')
    prop.Value = produced_prop.Value if produced_prop else False

    # LastCloneTime
    prop = conveyor.getProperty('LastCloneTime')
    if not prop:
        prop = conveyor.createProperty(VC_REAL, 'LastCloneTime')
    prop.Value = 0.0

    # CloneCount (linked to template property for tracking)
    prop = conveyor.getProperty('CloneCount')
    if not prop:
        prop = conveyor.createProperty(VC_INTEGER, 'CloneCount')
    prop.Value = clone_count_prop.Value if clone_count_prop else 0

    # Index
    prop = conveyor.getProperty('Index')
    if not prop:
        prop = conveyor.createProperty(VC_INTEGER, 'Index')
    prop.Value = index

def OnRun():
    # TEMPLATE: Replace hardcoded wait time '50' with InputConveyor.opcuaWaitCycles attribute from metamodel
    # Wait for OPC-UA data with delay loop (following pattern from other components)
    for i in range(50):  # 5 seconds max
        # TEMPLATE: Replace hardcoded property name 'Input_Conveyor_Location' with InputConveyor.locationPropertyName attribute from metamodel
        input_location_prop = comp.getProperty('inputconveyorProperties')
        if input_location_prop and input_location_prop.Value and input_location_prop.Value != "[]":
            clone_conveyors()
            break
        delay(0.1)
    
    # Main run loop
    while True:
        for conveyor in cloned_conveyors:
            process_conveyor(conveyor)
        delay(1)

def process_conveyor(conveyor):
    clone_time_interval = conveyor.getProperty('CloneTimeInterval').Value
    last_clone_time = conveyor.getProperty('LastCloneTime').Value
    current_time = sim.SimTime

    if current_time - last_clone_time >= clone_time_interval:
        clone_component(conveyor)
        conveyor.getProperty('LastCloneTime').Value = current_time

def clone_component(conveyor):
    global cloned_components
    
    # Find original component to clone - try both possible names
    original_component = app.findComponent('Component1')
    if not original_component:
        original_component = app.findComponent('Component_1')
    
    if original_component:
        # Clone with unique geometry (shared=0) to avoid attachment conflicts
        cloned_component = original_component.clone(0)

        product_type = conveyor.getProperty('ProductType').Value
        
        clone_count_prop = conveyor.getProperty('CloneCount')
        clone_count_prop.Value += 1
        clone_count = clone_count_prop.Value

        unique_name = '{0}_{1}'.format(product_type, clone_count)
        cloned_component.Name = unique_name

        # Set ProductType on cloned component
        prop = cloned_component.getProperty('ProductType')
        if not prop:
            prop = cloned_component.createProperty(VC_STRING, 'ProductType')
        prop.Value = product_type

        # Position on conveyor
        conveyor_height = conveyor.ConveyorHeight
        conveyor_matrix = conveyor.WorldPositionMatrix

        spawn_x = conveyor_matrix.P.X
        spawn_y = conveyor_matrix.P.Y
        spawn_z = conveyor_matrix.P.Z + conveyor_height

        m = vcMatrix.new()
        m.translateAbs(spawn_x, spawn_y, spawn_z)
        cloned_component.PositionMatrix = m

        # Update Produced property
        produced_prop = conveyor.getProperty('Produced')
        if produced_prop:
            produced_prop.Value = True

        # Update template's Produced# property
        index = conveyor.getProperty('Index').Value
        produced_prop_name = 'Produced{}'.format(index)
        produced_prop_template = comp.getProperty(produced_prop_name)
        if produced_prop_template:
            produced_prop_template.Value = True

        cloned_components.append(cloned_component)

def OnReset():
    global cloned_conveyors, cloned_components

    # Delete cloned conveyors
    for conveyor in cloned_conveyors:
        try: conveyor.delete()
        except: pass
    cloned_conveyors = []

    # Delete cloned components
    for component in cloned_components:
        try: component.delete()
        except: pass
    cloned_components = []

    # Reset properties
    quantity_prop = comp.getProperty('InputConveyorQuantity')
    if quantity_prop:
        for i in range(1, min(quantity_prop.Value, MAX_CONVEYORS) + 1):
            # Reset Produced#
            prop = comp.getProperty('Produced{}'.format(i))
            if prop:
                prop.Value = False

            # Reset CloneCount#
            prop = comp.getProperty('CloneCount{}'.format(i))
            if prop:
                prop.Value = 0
'''


# Idle Location script
IdleLocation = '''from vcScript import *
import vcMatrix as mat 
import vcVector
import math

comp = getComponent()
app = getApplication()
idles = []

def OnStart():
    # TEMPLATE: Replace hardcoded property name 'IdleProperties' with IdleLocation.opcuaPropertyName attribute from metamodel
    idle_prop = comp.getProperty('idleProperties')
    if idle_prop:
        idle_prop.OnChanged = create_idles

def create_idles(prop):
    global idles
    
    if not prop.Value or prop.Value == "[]":
        return
    
    # Clean up existing
    for idle in idles:
        try: idle.delete()
        except: pass
    idles = []
    
    # Parse and create
    try:
        idle_properties = eval(prop.Value)
    except:
        return
    
    # Process all idle properties starting from the first element
    for props in idle_properties:
        new_idle = comp.clone()
        if new_idle:
            new_idle.Name = props['Name']
            new_idle.Visible = True  # Make clone visible
            
            # Create a new matrix
            mtx = mat.new()
            
            # First, apply rotation around Z-axis
            mtx.rotateAbsZ(props.get('Rz', 0))
            
            # Then, apply translation
            mtx.translateAbs(props.get('X', 0), props.get('Y', 0), 0)
            
            # Set the PositionMatrix of the cloned idle
            new_idle.PositionMatrix = mtx
            
            idles.append(new_idle)
    
    app.render()

def OnRun():
    # TEMPLATE: Replace hardcoded wait time '50' with IdleLocation.opcuaWaitCycles attribute from metamodel
    # Wait for OPC-UA data
    for i in range(50):  # 5 seconds max
        # TEMPLATE: Replace hardcoded property name 'idleProperties' with IdleLocation.opcuaPropertyName attribute from metamodel
        idle_prop = comp.getProperty('idleProperties')
        if idle_prop and idle_prop.Value and idle_prop.Value != "[]":
            create_idles(idle_prop)
            break
        delay(0.1)

def OnReset():
    global idles
    for idle in idles:
        try:
            idle.delete()
        except:
            pass
    idles = []

def OnSignal( signal ):
    pass
'''


# Mobile Robot Resource script
Robot = '''from vcScript import *
import vcMatrix as mat
import vcVector
import math
import heapq

# Initialize global variables
comp = getComponent()
app = getApplication()
sim = getSimulation()

# Helper function to check if a component is a conveyor
def is_conveyor(component_name):
    """Check if a component name indicates it's a conveyor by looking for 'conveyor' in the name (case-insensitive)"""
    return 'conveyor' in component_name.lower()

# Helper function to check if a component is an input conveyor
def is_input_conveyor(component_name):
    """Check if a component name indicates it's an input conveyor by looking for 'input' in the name (case-insensitive)"""
    return 'input' in component_name.lower()

# Helper function to check if a component is an output conveyor
def is_output_conveyor(component_name):
    """Check if a component name indicates it's an output conveyor by looking for 'output' in the name (case-insensitive)"""
    return 'output' in component_name.lower()

robots = []
robot_states = {}
cloned_robots = []

# TEMPLATE: Replace hardcoded MAX_ROBOTS '15' with Robot.maxInstances attribute from metamodel
# Maximum supported robots
MAX_ROBOTS = 15

# Global reservation system for conflict-free pathfinding
pathway_reservations = {}  # pathway_name -> {robot_index: reservation_time}
robot_planned_paths = {}   # robot_index -> [pathway_names_in_order]
coordination_lock = False  # Prevents simultaneous path planning

# TEMPLATE: Replace hardcoded property names list with Robot.opcuaProperties attribute names from metamodel instances
# List of property names to create for each robot
property_names = [
    'Target',
    'Stop',
    'CarryingProduct',
    'CarriedProduct',
    'BatteryLevel',
    'Location',
    'NextLocation',
    'Priority',
    'MaxSpeed'
]

def OnStart():
    global comp
    # TEMPLATE: Replace hardcoded property names 'RobotQuantity' and 'InitialPositions' with Robot.quantityPropertyName and Robot.positionsPropertyName attributes from metamodel
    # Setup property change handlers
    robot_quantity_prop = comp.getProperty('RobotQuantity')
    if robot_quantity_prop:
        robot_quantity_prop.OnChanged = lambda prop: clone_robots()
    
    positions_prop = comp.getProperty('InitialPositions')
    if positions_prop:
        positions_prop.OnChanged = lambda prop: update_robot_positions()

# Function to get the robot's index based on its name
def get_robot_index(robot_name):
    if robot_name == 'Mobile Robot Resource':
        return 1
    else:
        return int(robot_name.split('#')[-1])

# Function to parse the InitialPositions string
def parse_initial_positions(positions_str):
    positions_list = []

    # Remove newlines and spaces
    positions_str = positions_str.replace('\\n', '').replace(' ', '')
    # Remove starting '[' and ending ']'
    positions_str = positions_str.strip('[]')

    # Split into individual position entries
    positions_str = positions_str.replace('},{', '}|{')
    entries = positions_str.split('|')

    for entry in entries:
        entry = entry.strip('{}')
        position_data = {}
        # Split into key-value pairs
        pairs = entry.split(',')
        for pair in pairs:
            # Split key and value
            if ':' in pair:
                key, value = pair.split(':', 1)
                key = key.strip('"')
                value = value.strip()
                # Remove quotes from value if value is a string
                if value.startswith('"') and value.endswith('"'):
                    value = value.strip('"')
                # Convert to float if key is 'X', 'Y', 'Rz'
                if key in ['X', 'Y', 'Rz']:
                    try:
                        position_data[key] = float(value)
                    except ValueError:
                        position_data[key] = 0.0
                else:
                    # For 'Name', store the value
                    position_data[key] = value
        positions_list.append(position_data)
    return positions_list

def clone_robots():
    global cloned_robots, robots, comp, app
    
    # Get the RobotQuantity property
    robot_quantity_prop = comp.getProperty('RobotQuantity')
    robot_quantity = robot_quantity_prop.Value if robot_quantity_prop else 0

    # Check if robot_quantity is valid
    if robot_quantity <= 0 or robot_quantity > MAX_ROBOTS:
        return

    # Clean up existing clones
    for robot in cloned_robots:
        try: robot.delete()
        except: pass
    cloned_robots = []
    robots = []

    # Try to get positions from IdleProperties
    idle_location_template = app.findComponent('_Template_IdleLocation')
    positions_list = []
    
    if idle_location_template:
        idle_prop = idle_location_template.getProperty('idleProperties')
        if idle_prop and idle_prop.Value and idle_prop.Value != "[]":
            try:
                positions_list = eval(idle_prop.Value)
            except:
                pass
    
    # If no positions from IdleProperties, use InitialPositions
    if not positions_list:
        positions_prop = comp.getProperty('InitialPositions')
        if positions_prop and positions_prop.Value:
            positions_list = parse_initial_positions(positions_prop.Value)

    # Clone robots - ALL robots should be clones
    for i in range(1, robot_quantity + 1):
        # Clone the robot without scripts
        robot = comp.clone(0)
        
        if i == 1:
            # First robot named without number
            robot.Name = 'Mobile Robot Resource'
        else:
            robot.Name = 'Mobile Robot Resource #{0}'.format(i)
        
        robot.Visible = True  # Make clone visible
        cloned_robots.append(robot)
        robots.append(robot)
        
        # Create Vehicle behavior for cloned robot
        if not robot.findBehaviour("Vehicle"):
            vehicle = robot.createBehaviour(VC_VEHICLE, "Vehicle")
            # TEMPLATE: Replace hardcoded vehicle properties with values from metamodel Robot.acceleration, Robot.deceleration, Robot.maxSpeed, Robot.interpolation attributes
            vehicle.Acceleration = 300.0
            vehicle.Deceleration = 300.0
            vehicle.MaxSpeed = 800.0
            vehicle.Interpolation = 0.15

        # Set initial positions
        position_index = i - 1
        if position_index < len(positions_list):
            position_data = positions_list[position_index]
            X = position_data.get('X', 0.0)
            Y = position_data.get('Y', 0.0)
            Rz = position_data.get('Rz', 0.0)

            # Create transformation matrix
            m = mat.new()
            m.translateAbs(X, Y, 0.0)
            m.rotateRelZ(math.radians(Rz))
            robot.PositionMatrix = m
        else:
            # If not enough positions provided, use offset positions
            offset = (i - 1) * 2000
            m = mat.new()
            m.translateAbs(offset, 0, 0)
            robot.PositionMatrix = m

    app.render()

def update_robot_positions():
    """Update robot positions when InitialPositions property changes"""
    global comp
    if not robots:
        return
        
    positions_prop = comp.getProperty('InitialPositions')
    if not positions_prop or not positions_prop.Value:
        return
        
    positions_list = parse_initial_positions(positions_prop.Value)
    
    for i, robot in enumerate(robots):
        if i < len(positions_list):
            position_data = positions_list[i]
            X = position_data.get('X', 0.0)
            Y = position_data.get('Y', 0.0)
            Rz = position_data.get('Rz', 0.0)

            m = mat.new()
            m.translateAbs(X, Y, 0.0)
            m.rotateRelZ(math.radians(Rz))
            robot.PositionMatrix = m

# Helper functions
def get_robot_property(prop_name, robot_index):
    global comp
    unique_prop_name = '{0}{1}'.format(prop_name, robot_index)
    prop = comp.getProperty(unique_prop_name)
    return prop

def set_robot_property(prop_name, value, robot_index):
    prop = get_robot_property(prop_name, robot_index)
    if prop:
        prop.Value = value

def get_robot_property_value(prop_name, robot_index):
    prop = get_robot_property(prop_name, robot_index)
    if prop:
        return prop.Value
    return None

def getRobotPosition(robot):
    m = robot.WorldPositionMatrix
    return m.P

def normalize_vector(v):
    length = math.sqrt(v.X**2 + v.Y**2 + v.Z**2)
    if length == 0:
        return vcVector.new(0, 0, 0)
    return vcVector.new(v.X / length, v.Y / length, v.Z / length)

def vector_add(v1, v2):
    return vcVector.new(v1.X + v2.X, v1.Y + v2.Y, v1.Z + v2.Z)

def vector_subtract(v1, v2):
    return vcVector.new(v1.X - v2.X, v1.Y - v2.Y, v1.Z - v2.Z)

def vector_multiply(v, scalar):
    return vcVector.new(v.X * scalar, v.Y * scalar, v.Z * scalar)

def vector_length(v):
    return math.sqrt(v.X**2 + v.Y**2 + v.Z**2)

def find_shortest_transition_point(robot_pos, current_pathway, next_pathway):
    """Find the shortest transition point between current pathway and next pathway/destination - optimized for performance"""
    # Get current pathway geometry
    curr_pos = current_pathway.WorldPositionMatrix.P
    curr_length1 = current_pathway.getProperty('Length1').Value if current_pathway.getProperty('Length1') else 0
    curr_length2 = current_pathway.getProperty('Length2').Value if current_pathway.getProperty('Length2') else 0
    curr_width1 = current_pathway.getProperty('Width1').Value if current_pathway.getProperty('Width1') else 0
    curr_width2 = current_pathway.getProperty('Width2').Value if current_pathway.getProperty('Width2') else 0
    
    if is_conveyor(current_pathway.Name):
        conveyor_length = current_pathway.getProperty('ConveyorLength').Value if current_pathway.getProperty('ConveyorLength') else 0
        conveyor_width = current_pathway.getProperty('ConveyorWidth').Value if current_pathway.getProperty('ConveyorWidth') else 0
        curr_length1 = curr_length2 = conveyor_length / 2
        curr_width1 = curr_width2 = conveyor_width

    curr_N = current_pathway.WorldPositionMatrix.N
    curr_direction = normalize_vector(curr_N)
    curr_perpendicular = vcVector.new(-curr_direction.Y, curr_direction.X, 0)
    
    # Calculate current pathway boundary points
    curr_start_center = vector_subtract(curr_pos, vector_multiply(curr_direction, curr_length1))
    curr_end_center = vector_add(curr_pos, vector_multiply(curr_direction, curr_length2))
    curr_max_width = max(curr_width1, curr_width2) if curr_width1 and curr_width2 else 1000
    curr_half_width = curr_max_width / 2
    
    # OPTIMIZED: Reduced points for better performance - only essential boundary points
    current_boundary_points = []
    
    # Long sides (left and right edges) - reduced from 11 to 6 points
    for i in range(6):  # 6 points along each long side
        t = i / 5.0
        # Left edge
        left_point = vector_add(
            vector_add(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width)),
            vector_multiply(vector_subtract(
                vector_add(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width)),
                vector_add(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width))
            ), t)
        )
        current_boundary_points.append(left_point)
        
        # Right edge
        right_point = vector_add(
            vector_subtract(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width)),
            vector_multiply(vector_subtract(
                vector_subtract(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width)),
                vector_subtract(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width))
            ), t)
        )
        current_boundary_points.append(right_point)
    
    # Short sides (start and end edges) - reduced from 7 to 4 points
    for i in range(4):  # 4 points along each short side
        t = i / 3.0
        # Start edge (short side)
        start_point = vector_add(
            vector_add(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width)),
            vector_multiply(vector_subtract(
                vector_subtract(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width)),
                vector_add(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width))
            ), t)
        )
        current_boundary_points.append(start_point)
        
        # End edge (short side)
        end_point = vector_add(
            vector_add(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width)),
            vector_multiply(vector_subtract(
                vector_subtract(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width)),
                vector_add(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width))
            ), t)
        )
        current_boundary_points.append(end_point)
    
    # Get next pathway/destination position
    if next_pathway:
        next_pos = next_pathway.WorldPositionMatrix.P
        
        # If next is also a pathway (not conveyor), find closest boundary-to-boundary distance
        if next_pathway.Name.startswith('Pathway Area') or next_pathway.Name.startswith('Idle Location'):
            # Get next pathway geometry
            next_length1 = next_pathway.getProperty('Length1').Value if next_pathway.getProperty('Length1') else 0
            next_length2 = next_pathway.getProperty('Length2').Value if next_pathway.getProperty('Length2') else 0
            next_width1 = next_pathway.getProperty('Width1').Value if next_pathway.getProperty('Width1') else 0
            next_width2 = next_pathway.getProperty('Width2').Value if next_pathway.getProperty('Width2') else 0
            
            next_N = next_pathway.WorldPositionMatrix.N
            next_direction = normalize_vector(next_N)
            next_perpendicular = vcVector.new(-next_direction.Y, next_direction.X, 0)
            
            next_start_center = vector_subtract(next_pos, vector_multiply(next_direction, next_length1))
            next_end_center = vector_add(next_pos, vector_multiply(next_direction, next_length2))
            next_max_width = max(next_width1, next_width2) if next_width1 and next_width2 else 1000
            next_half_width = next_max_width / 2
            
            # Create boundary points for next pathway - OPTIMIZED
            next_boundary_points = []
            
            # Long sides of next pathway - reduced from 11 to 6 points
            for i in range(6):
                t = i / 5.0
                # Left edge
                left_point = vector_add(
                    vector_add(next_start_center, vector_multiply(next_perpendicular, next_half_width)),
                    vector_multiply(vector_subtract(
                        vector_add(next_end_center, vector_multiply(next_perpendicular, next_half_width)),
                        vector_add(next_start_center, vector_multiply(next_perpendicular, next_half_width))
                    ), t)
                )
                next_boundary_points.append(left_point)
                
                # Right edge
                right_point = vector_add(
                    vector_subtract(next_start_center, vector_multiply(next_perpendicular, next_half_width)),
                    vector_multiply(vector_subtract(
                        vector_subtract(next_end_center, vector_multiply(next_perpendicular, next_half_width)),
                        vector_subtract(next_start_center, vector_multiply(next_perpendicular, next_half_width))
                    ), t)
                )
                next_boundary_points.append(right_point)
            
            # Short sides of next pathway - reduced from 7 to 4 points
            for i in range(4):
                t = i / 3.0
                # Start edge
                start_point = vector_add(
                    vector_add(next_start_center, vector_multiply(next_perpendicular, next_half_width)),
                    vector_multiply(vector_subtract(
                        vector_subtract(next_start_center, vector_multiply(next_perpendicular, next_half_width)),
                        vector_add(next_start_center, vector_multiply(next_perpendicular, next_half_width))
                    ), t * 3.0 / 3.0)
                )
                next_boundary_points.append(start_point)
                
                end_point = vector_add(
                    vector_add(next_end_center, vector_multiply(next_perpendicular, next_half_width)),
                    vector_multiply(vector_subtract(
                        vector_subtract(next_end_center, vector_multiply(next_perpendicular, next_half_width)),
                        vector_add(next_end_center, vector_multiply(next_perpendicular, next_half_width))
                    ), t * 3.0 / 3.0)
                )
                next_boundary_points.append(end_point)
            
            # Find the shortest distance between any two boundary points
            min_distance = float('inf')
            best_current_point = None
            best_next_point = None
            
            for curr_point in current_boundary_points:
                for next_point in next_boundary_points:
                    distance = vector_length(vector_subtract(next_point, curr_point))
                    if distance < min_distance:
                        min_distance = distance
                        best_current_point = curr_point
                        best_next_point = next_point
            
            return best_current_point, best_next_point
        
        else:
            # Next is a conveyor - find closest point on current pathway to conveyor center
            best_exit_point = min(current_boundary_points, 
                                key=lambda p: vector_length(vector_subtract(next_pos, p)))
            return best_exit_point, next_pos
    
    else:
        # No next pathway - use pathway center as default
        return curr_pos, curr_pos

def calculate_smart_entry_exit_points(robot_pos, current_pathway, next_pathway=None, previous_pathway=None):
    """Calculate smart entry and exit points focusing on shortest transitions"""
    
    # Find the optimal transition point to next pathway
    if next_pathway:
        exit_point, next_entry_point = find_shortest_transition_point(robot_pos, current_pathway, next_pathway)
    else:
        # No next pathway - just find closest boundary point to robot
        curr_pos = current_pathway.WorldPositionMatrix.P
        exit_point = curr_pos  # Default to center
    
    # For entry point, find the closest accessible point on current pathway boundary
    # (This will be used when robot is outside the pathway)
    curr_pos = current_pathway.WorldPositionMatrix.P
    curr_length1 = current_pathway.getProperty('Length1').Value if current_pathway.getProperty('Length1') else 0
    curr_length2 = current_pathway.getProperty('Length2').Value if current_pathway.getProperty('Length2') else 0
    curr_width1 = current_pathway.getProperty('Width1').Value if current_pathway.getProperty('Width1') else 0
    curr_width2 = current_pathway.getProperty('Width2').Value if current_pathway.getProperty('Width2') else 0
    
    if is_conveyor(current_pathway.Name):
        conveyor_length = current_pathway.getProperty('ConveyorLength').Value if current_pathway.getProperty('ConveyorLength') else 0
        conveyor_width = current_pathway.getProperty('ConveyorWidth').Value if current_pathway.getProperty('ConveyorWidth') else 0
        curr_length1 = curr_length2 = conveyor_length / 2
        curr_width1 = curr_width2 = conveyor_width

    curr_N = current_pathway.WorldPositionMatrix.N
    curr_direction = normalize_vector(curr_N)
    curr_perpendicular = vcVector.new(-curr_direction.Y, curr_direction.X, 0)
    
    curr_start_center = vector_subtract(curr_pos, vector_multiply(curr_direction, curr_length1))
    curr_end_center = vector_add(curr_pos, vector_multiply(curr_direction, curr_length2))
    curr_max_width = max(curr_width1, curr_width2) if curr_width1 and curr_width2 else 1000
    curr_half_width = curr_max_width / 2
    
    # Create all boundary points for entry consideration
    entry_candidate_points = []
    
    # Add points along all edges - OPTIMIZED for performance
    for i in range(5):  # Reduced from 8 to 5 points along each edge
        t = i / 4.0
        
        # Long sides
        left_point = vector_add(
            vector_add(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width)),
            vector_multiply(vector_subtract(
                vector_add(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width)),
                vector_add(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width))
            ), t)
        )
        entry_candidate_points.append(left_point)
        
        right_point = vector_add(
            vector_subtract(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width)),
            vector_multiply(vector_subtract(
                vector_subtract(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width)),
                vector_subtract(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width))
            ), t)
        )
        entry_candidate_points.append(right_point)
        
        # Short sides - fewer points
        if i < 3:  # Reduced from 5 to 3 points on short sides
            start_point = vector_add(
                vector_add(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width)),
                vector_multiply(vector_subtract(
                    vector_subtract(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width)),
                    vector_add(curr_start_center, vector_multiply(curr_perpendicular, curr_half_width))
                ), t * 2.0 / 2.0)
            )
            entry_candidate_points.append(start_point)
            
            end_point = vector_add(
                vector_add(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width)),
                vector_multiply(vector_subtract(
                    vector_subtract(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width)),
                    vector_add(curr_end_center, vector_multiply(curr_perpendicular, curr_half_width))
                ), t * 2.0 / 2.0)
            )
            entry_candidate_points.append(end_point)
    
    # Find best entry point (closest to robot)
    entry_point = min(entry_candidate_points, key=lambda p: vector_length(vector_subtract(p, robot_pos)))
    
    return entry_point, exit_point

def calculate_intelligent_pathway_points(robot_pos, current_pathway, next_pathway=None, previous_pathway=None):
    """Calculate intelligent turning and transition points within pathways for optimal movement"""
    pos = current_pathway.WorldPositionMatrix.P
    length1 = current_pathway.getProperty('Length1').Value if current_pathway.getProperty('Length1') else 0
    length2 = current_pathway.getProperty('Length2').Value if current_pathway.getProperty('Length2') else 0
    width1 = current_pathway.getProperty('Width1').Value if current_pathway.getProperty('Width1') else 0
    width2 = current_pathway.getProperty('Width2').Value if current_pathway.getProperty('Width2') else 0
    
    if is_conveyor(current_pathway.Name):
        conveyor_length = current_pathway.getProperty('ConveyorLength').Value if current_pathway.getProperty('ConveyorLength') else 0
        conveyor_width = current_pathway.getProperty('ConveyorWidth').Value if current_pathway.getProperty('ConveyorWidth') else 0
        length1 = length2 = conveyor_length / 2
        width1 = width2 = conveyor_width

    N = current_pathway.WorldPositionMatrix.N
    direction_vector = normalize_vector(N)
    perpendicular_vector = vcVector.new(-direction_vector.Y, direction_vector.X, 0)
    
    # Calculate pathway boundaries
    start_center = vector_subtract(pos, vector_multiply(direction_vector, length1))
    end_center = vector_add(pos, vector_multiply(direction_vector, length2))
    max_width = max(width1, width2) if width1 and width2 else 1000
    half_width = max_width / 2
    
    # Use the improved shortest transition point calculation
    if next_pathway:
        # Get the optimal exit point using shortest transition calculation
        exit_point, _ = find_shortest_transition_point(robot_pos, current_pathway, next_pathway)
    else:
        # Default to pathway center if no next pathway
        exit_point = pos
    
    # Find optimal entry point from available boundary points
    entry_candidates = []
    
    # Create boundary points for entry consideration (optimized coverage)
    for i in range(6):  # Reduced from 10 to 6 points along each edge
        t = i / 5.0  # 0 to 1
        
        # Long edges (left and right sides)
        left_point = vector_add(
            vector_add(start_center, vector_multiply(perpendicular_vector, half_width)),
            vector_multiply(vector_subtract(
                vector_add(end_center, vector_multiply(perpendicular_vector, half_width)),
                vector_add(start_center, vector_multiply(perpendicular_vector, half_width))
            ), t)
        )
        entry_candidates.append(left_point)
        
        right_point = vector_add(
            vector_subtract(start_center, vector_multiply(perpendicular_vector, half_width)),
            vector_multiply(vector_subtract(
                vector_subtract(end_center, vector_multiply(perpendicular_vector, half_width)),
                vector_subtract(start_center, vector_multiply(perpendicular_vector, half_width))
            ), t)
        )
        entry_candidates.append(right_point)
        
        # Short edges (start and end sides) - fewer points
        if i < 4:  # Reduced from 6 to 4 points along short edges
            t_short = i / 3.0
            start_edge_point = vector_add(
                vector_add(start_center, vector_multiply(perpendicular_vector, half_width)),
                vector_multiply(vector_subtract(
                    vector_subtract(start_center, vector_multiply(perpendicular_vector, half_width)),
                    vector_add(start_center, vector_multiply(perpendicular_vector, half_width))
                ), t_short)
            )
            entry_candidates.append(start_edge_point)
            
            end_edge_point = vector_add(
                vector_add(end_center, vector_multiply(perpendicular_vector, half_width)),
                vector_multiply(vector_subtract(
                    vector_subtract(end_center, vector_multiply(perpendicular_vector, half_width)),
                    vector_add(end_center, vector_multiply(perpendicular_vector, half_width))
                ), t_short)
            )
            entry_candidates.append(end_edge_point)
    
    # Find best entry point (closest to robot)
    entry_point = min(entry_candidates, key=lambda p: vector_length(vector_subtract(p, robot_pos)))
    
    # Calculate intermediate points for smooth navigation
    intermediate_points = []
    
    # Calculate distance between entry and exit
    entry_to_exit_distance = vector_length(vector_subtract(exit_point, entry_point))
    
    # Only add intermediate points if the path is long enough to benefit from them
    if entry_to_exit_distance > 2000:  # If path is longer than 2000 units
        # Calculate if significant direction change is needed
        entry_direction = vector_subtract(entry_point, robot_pos)
        exit_direction = vector_subtract(exit_point, entry_point)
        
        if vector_length(entry_direction) > 0 and vector_length(exit_direction) > 0:
            entry_norm = normalize_vector(entry_direction)
            exit_norm = normalize_vector(exit_direction)
            
            # Calculate angle between directions
            dot_product = entry_norm.X * exit_norm.X + entry_norm.Y * exit_norm.Y
            
            # If significant direction change (< 60% similarity)
            if dot_product < 0.6:
                # Add strategic intermediate point for smoother turning
                # Position it 30% along the path and slightly toward center
                intermediate = vector_add(entry_point, vector_multiply(vector_subtract(exit_point, entry_point), 0.3))
                
                # Adjust toward pathway center for smoother navigation
                direction_to_center = vector_subtract(pos, intermediate)
                if vector_length(direction_to_center) > 0:
                    center_adjustment = vector_multiply(normalize_vector(direction_to_center), 200)
                    intermediate = vector_add(intermediate, center_adjustment)
                
                intermediate_points.append(intermediate)
    
    return entry_point, intermediate_points, exit_point

def get_optimal_pathway_direction(robot, robot_index, current_pathway, pathways, current_index):
    """Enhanced pathway direction calculation with smart entry/exit points"""
    robot_pos = getRobotPosition(robot)
    
    # Get next and previous pathways for context
    next_pathway = pathways[current_index + 1] if current_index < len(pathways) - 1 else None
    previous_pathway = pathways[current_index - 1] if current_index > 0 else None
    
    # Calculate intelligent pathway points
    entry_point, intermediate_points, exit_point = calculate_intelligent_pathway_points(
        robot_pos, current_pathway, next_pathway, previous_pathway)
    
    # Return the exit point as the target (robots will path to this optimally)
    return exit_point

def find_pathway_intersection_point(current_pathway, next_pathway):
    """Find the optimal intersection point between two pathways for smooth transitions"""
    # Get current pathway geometry
    current_pos = current_pathway.WorldPositionMatrix.P
    current_length1 = current_pathway.getProperty('Length1').Value if current_pathway.getProperty('Length1') else 0
    current_length2 = current_pathway.getProperty('Length2').Value if current_pathway.getProperty('Length2') else 0
    
    if is_conveyor(current_pathway.Name):
        conveyor_length = current_pathway.getProperty('ConveyorLength').Value if current_pathway.getProperty('ConveyorLength') else 0
        current_length1 = current_length2 = conveyor_length / 2

    current_N = current_pathway.WorldPositionMatrix.N
    current_direction = normalize_vector(current_N)
    current_start = vector_subtract(current_pos, vector_multiply(current_direction, current_length1))
    current_end = vector_add(current_pos, vector_multiply(current_direction, current_length2))
    
    # Get next pathway geometry
    next_pos = next_pathway.WorldPositionMatrix.P
    next_length1 = next_pathway.getProperty('Length1').Value if next_pathway.getProperty('Length1') else 0
    next_length2 = next_pathway.getProperty('Length2').Value if next_pathway.getProperty('Length2') else 0
    
    if is_conveyor(next_pathway.Name):
        conveyor_length = next_pathway.getProperty('ConveyorLength').Value if next_pathway.getProperty('ConveyorLength') else 0
        next_length1 = next_length2 = conveyor_length / 2

    next_N = next_pathway.WorldPositionMatrix.N
    next_direction = normalize_vector(next_N)
    next_start = vector_subtract(next_pos, vector_multiply(next_direction, next_length1))
    next_end = vector_add(next_pos, vector_multiply(next_direction, next_length2))
    
    # Find the closest points between the two pathways
    potential_intersections = [
        (current_start, next_start),
        (current_start, next_end),
        (current_end, next_start),
        (current_end, next_end),
        (current_pos, next_pos)  # Include pathway centers
    ]
    
    # Find the intersection with minimum distance
    min_distance = float('inf')
    best_intersection = None
    
    for current_point, next_point in potential_intersections:
        distance = vector_length(vector_subtract(current_point, next_point))
        if distance < min_distance:
            min_distance = distance
            best_intersection = (current_point, next_point)
    
    # Return the midpoint of the best intersection
    if best_intersection:
        current_point, next_point = best_intersection
        midpoint = vector_add(current_point, vector_multiply(vector_subtract(next_point, current_point), 0.5))
        return midpoint
    
    # Fallback to pathway centers
    return vector_add(current_pos, vector_multiply(vector_subtract(next_pos, current_pos), 0.5))

def can_transition_directly(robot_pos, current_pathway, next_pathway, transition_threshold=1500):
    """Check if robot can transition directly between pathways without going to endpoints"""
    intersection_point = find_pathway_intersection_point(current_pathway, next_pathway)
    distance_to_intersection = vector_length(vector_subtract(robot_pos, intersection_point))
    
    return distance_to_intersection < transition_threshold

def calculate_side_by_side_offset(robot, robot_index, pathway):
    """Minimal side-by-side calculation - only for robots actually in same pathway (prevents zigzag)"""
    robot_radius = 400
    base_lane_spacing = 1200
    
    # Only apply if robot is not in coordination mode
    if robot_index in robot_states and robot_states[robot_index].get('in_coordination', False):
        return vcVector.new(0, 0, 0)
    
    # Get pathway direction and perpendicular vector
    pathway_pos = pathway.WorldPositionMatrix.P
    pathway_N = pathway.WorldPositionMatrix.N
    direction = normalize_vector(pathway_N)
    perpendicular = vcVector.new(-direction.Y, direction.X, 0)
    
    # Find robots ACTUALLY in the same pathway (not approaching)
    robots_in_pathway = []
    robot_positions = {}
    robot_pos = getRobotPosition(robot)
    
    for other_robot in robots:
        other_robot_index = get_robot_index(other_robot.Name)
        other_location = get_robot_property_value('Location', other_robot_index)
        
        # Only include robots actually in the same pathway
        if other_location == pathway.Name:
            # Also check if they're not in coordination mode
            if not (other_robot_index in robot_states and robot_states[other_robot_index].get('in_coordination', False)):
                robots_in_pathway.append(other_robot_index)
                robot_positions[other_robot_index] = getRobotPosition(other_robot)
    
    # If only one robot or no other robots, no offset needed
    if len(robots_in_pathway) <= 1:
        return vcVector.new(0, 0, 0)
    
    # Sort robots deterministically for consistent lane assignment
    def sort_key(robot_idx):
        priority = get_robot_property_value('Priority', robot_idx)
        return (priority, robot_idx)
    
    robots_in_pathway.sort(key=sort_key)
    
    try:
        lane_index = robots_in_pathway.index(robot_index)
        total_robots = len(robots_in_pathway)
        
        # Calculate offset with reduced spacing to prevent conflicts
        if total_robots % 2 == 1:
            center_offset = (total_robots - 1) // 2
            offset_multiplier = lane_index - center_offset
        else:
            offset_multiplier = lane_index - (total_robots - 1) / 2
        
        # Reduced offset distance to prevent interference with coordination
        offset_distance = offset_multiplier * (base_lane_spacing * 0.8)
        offset = vector_multiply(perpendicular, offset_distance)
        
        return offset
        
    except ValueError:
        return vcVector.new(0, 0, 0)

def calculate_avoidance_offset(robot, robot_index):
    """Calculate smooth avoidance offset for path planning only - no real-time position changes"""
    robot_pos = getRobotPosition(robot)
    avoidance_offset = vcVector.new(0, 0, 0)
    robot_radius = 400  # Reduced from 600 for more accurate collision boundaries
    safe_distance = 1000  # Safe separation distance
    
    for other_robot in robots:
        if other_robot != robot:
            other_robot_index = get_robot_index(other_robot.Name)
            other_pos = getRobotPosition(other_robot)
            
            # Calculate distance and direction between robots
            distance_vector = vector_subtract(robot_pos, other_pos)
            distance = vector_length(distance_vector)
            
            # Only apply avoidance if robots are within safe distance
            if distance < safe_distance and distance > 0:
                # Normalize the distance vector to get direction
                avoid_direction = normalize_vector(distance_vector)
                
                # Calculate smooth offset based on distance (closer = stronger avoidance)
                avoidance_strength = max(0, (safe_distance - distance) / safe_distance)
                offset_magnitude = avoidance_strength * 300  # Maximum 300 units offset
                
                # Apply avoidance with smooth falloff
                avoidance_offset = vector_add(avoidance_offset, vector_multiply(avoid_direction, offset_magnitude))
    
    return avoidance_offset

def check_velocity_obstacle_collision(robot, robot_index):
    """Velocity obstacle-based collision detection for proactive avoidance"""
    robot_pos = getRobotPosition(robot)
    robot_radius = 600  # Robot collision radius
    prediction_time = 3.0  # Look ahead time
    should_stop = False
    
    # Get robot's current velocity/direction
    vehicle = robot_states[robot_index]['vehicle'] if robot_index in robot_states else None
    if not vehicle:
        return False
    
    current_location = get_robot_property_value('Location', robot_index)
    next_location = get_robot_property_value('NextLocation', robot_index)
    
    for other_robot in robots:
        if other_robot != robot:
            other_robot_index = get_robot_index(other_robot.Name)
            other_pos = getRobotPosition(other_robot)
            other_vehicle = robot_states[other_robot_index]['vehicle'] if other_robot_index in robot_states else None
            
            if not other_vehicle:
                continue
                
            # Calculate relative position and velocity
            rel_pos = vector_subtract(other_pos, robot_pos)
            distance = vector_length(rel_pos)
            
            # Skip if robots are far apart
            if distance > 3000:
                continue
            
            # Get movement directions from current and next locations
            self_direction = get_movement_direction(robot, robot_index)
            other_direction = get_movement_direction(other_robot, other_robot_index)
            
            if not self_direction or not other_direction:
                continue
            
            # Calculate future positions
            self_future_pos = vector_add(robot_pos, vector_multiply(self_direction, 800 * prediction_time))
            other_future_pos = vector_add(other_pos, vector_multiply(other_direction, 800 * prediction_time))
            
            # Check if future collision is predicted
            future_distance = vector_length(vector_subtract(self_future_pos, other_future_pos))
            collision_threshold = robot_radius * 2 + 200  # Safety margin
            
            if future_distance < collision_threshold:
                # Predict collision - use priority and positioning for resolution
                self_priority = get_robot_property_value('Priority', robot_index)
                other_priority = get_robot_property_value('Priority', other_robot_index)
                
                # Priority-based resolution
                if self_priority < other_priority:
                    should_stop = True
                    break
                elif self_priority == other_priority:
                    # Use robot index for deterministic resolution
                    if robot_index > other_robot_index:
                        should_stop = True
                        break
    
    return should_stop

def calculate_coordinated_side_by_side_offset(robot, robot_index, pathway, partner_index):
    """Calculate coordinated side-by-side offset - both robots move to their lanes simultaneously"""
    robot_radius = 400
    lane_spacing = 1500  # Increased spacing for better clearance, especially in intersections
    robot_pos = getRobotPosition(robot)
    
    # Get pathway direction and perpendicular vector
    pathway_pos = pathway.WorldPositionMatrix.P
    pathway_N = pathway.WorldPositionMatrix.N
    direction = normalize_vector(pathway_N)
    perpendicular = vcVector.new(-direction.Y, direction.X, 0)
    
    # Find the partner robot
    partner_robot = next((r for r in robots if get_robot_index(r.Name) == partner_index), None)
    if not partner_robot:
        return vcVector.new(0, 0, 0)
    
    partner_pos = getRobotPosition(partner_robot)
    
    # Determine lane assignment based on priority and current positions
    self_priority = get_robot_property_value('Priority', robot_index)
    partner_priority = get_robot_property_value('Priority', partner_index)
    
    # Calculate relative position to determine natural side assignment
    relative_pos = vector_subtract(partner_pos, robot_pos)
    cross_product = relative_pos.X * direction.Y - relative_pos.Y * direction.X
    
    # Assign lanes based on priority and natural positioning
    if self_priority < partner_priority:
        # Higher priority robot takes right lane
        if cross_product > 0:
            # Partner is naturally to the right, so this robot goes left
            offset_direction = vector_multiply(perpendicular, -1)
        else:
            # Partner is naturally to the left, so this robot goes right
            offset_direction = perpendicular
    else:
        # Lower priority robot takes the opposite side
        if cross_product > 0:
            # Partner is naturally to the right, so this robot goes left
            offset_direction = vector_multiply(perpendicular, -1)
        else:
            # Partner is naturally to the left, so this robot goes right
            offset_direction = perpendicular
    
    # Calculate enhanced offset distance - larger for intersection areas
    distance_to_partner = vector_length(relative_pos)
    
    # Check if we're in an intersection area (multiple pathways nearby)
    nearby_pathways = 0
    for other_pathway in app.Components:
        if (other_pathway.Name.startswith('Pathway Area') or 
            other_pathway.Name.startswith('Idle Location')):
            pathway_distance = vector_length(vector_subtract(robot_pos, other_pathway.WorldPositionMatrix.P))
            if pathway_distance < 3000:  # Within intersection zone
                nearby_pathways += 1
    
    # Increase offset in intersection areas
    intersection_multiplier = 1.5 if nearby_pathways > 2 else 1.0
    
    # Gradual offset based on distance - closer robots get more offset
    if distance_to_partner < 1000:
        offset_distance = lane_spacing * 1.4 * intersection_multiplier  # Much more offset for very close robots
    elif distance_to_partner < 1500:
        offset_distance = lane_spacing * 1.2 * intersection_multiplier
    else:
        offset_distance = lane_spacing * intersection_multiplier
    
    return vector_multiply(offset_direction, offset_distance)

def calculate_in_place_side_by_side_offset(robot, robot_index, pathway):
    """Calculate side-by-side offset from current position without going back to pathway start"""
    robot_radius = 400
    base_lane_spacing = 1200  
    robot_pos = getRobotPosition(robot)
    
    # Get pathway direction and perpendicular vector
    pathway_pos = pathway.WorldPositionMatrix.P
    pathway_N = pathway.WorldPositionMatrix.N
    direction = normalize_vector(pathway_N)
    perpendicular = vcVector.new(-direction.Y, direction.X, 0)
    
    # Find the partner robot that triggered this side-by-side conversion
    partner_index = robot_states[robot_index].get('side_by_side_partner')
    if not partner_index:
        return vcVector.new(0, 0, 0)
    
    partner_robot = next((r for r in robots if get_robot_index(r.Name) == partner_index), None)
    if not partner_robot:
        return vcVector.new(0, 0, 0)
    
    partner_pos = getRobotPosition(partner_robot)
    
    # Determine which side to move to based on current relative positions
    relative_pos = vector_subtract(partner_pos, robot_pos)
    cross_product = relative_pos.X * direction.Y - relative_pos.Y * direction.X
    
    # Choose side based on current positions to minimize movement
    if cross_product > 0:
        # Partner is to the right, move to the left
        offset_direction = vector_multiply(perpendicular, -1)
    else:
        # Partner is to the left, move to the right
        offset_direction = perpendicular
    
    # Calculate gradual offset distance - start small and increase if needed
    distance_to_partner = vector_length(relative_pos)
    if distance_to_partner < 800:
        offset_distance = base_lane_spacing * 1.5  # Larger offset for very close robots
    elif distance_to_partner < 1200:
        offset_distance = base_lane_spacing
    else:
        offset_distance = base_lane_spacing * 0.8  # Smaller offset for distant robots
    
    return vector_multiply(offset_direction, offset_distance)

def check_bypass_possibility(robot, robot_index, stationary_robot, stationary_robot_index):
    """Check if a moving robot can safely bypass a stationary robot"""
    robot_pos = getRobotPosition(robot)
    stationary_pos = getRobotPosition(stationary_robot)
    robot_radius = 600  # Safety radius for bypass
    bypass_clearance = 1200  # Minimum clearance needed for bypass
    
    # Get movement direction
    movement_direction = get_movement_direction(robot, robot_index)
    if not movement_direction:
        return False
    
    # Calculate perpendicular directions for left/right bypass
    perp_right = vcVector.new(-movement_direction.Y, movement_direction.X, 0)
    perp_left = vcVector.new(movement_direction.Y, -movement_direction.X, 0)
    
    # Check both left and right bypass routes
    for bypass_direction in [perp_right, perp_left]:
        # Calculate bypass waypoint (offset to the side)
        bypass_offset = vector_multiply(bypass_direction, bypass_clearance)
        bypass_point = vector_add(robot_pos, bypass_offset)
        
        # Check if bypass point is clear of other robots
        bypass_clear = True
        for other_robot in robots:
            if other_robot != robot and other_robot != stationary_robot:
                other_pos = getRobotPosition(other_robot)
                distance_to_bypass = vector_length(vector_subtract(other_pos, bypass_point))
                
                if distance_to_bypass < robot_radius * 2:
                    bypass_clear = False
                    break
        
        # Check if there's enough space around the stationary robot
        if bypass_clear:
            # Calculate distance from bypass point to stationary robot
            distance_to_stationary = vector_length(vector_subtract(stationary_pos, bypass_point))
            
            if distance_to_stationary > robot_radius * 2:
                return True  # Bypass is possible
    
    return False  # No safe bypass route found

def calculate_bypass_offset(robot, robot_index, stationary_robot_index):
    """Calculate the lateral offset needed to bypass a stationary robot"""
    robot_pos = getRobotPosition(robot)
    stationary_robot = next((r for r in robots if get_robot_index(r.Name) == stationary_robot_index), None)
    
    if not stationary_robot:
        return vcVector.new(0, 0, 0)
    
    stationary_pos = getRobotPosition(stationary_robot)
    movement_direction = get_movement_direction(robot, robot_index)
    
    if not movement_direction:
        return vcVector.new(0, 0, 0)
    
    # Calculate which side to bypass (choose the side with more space)
    perp_right = vcVector.new(-movement_direction.Y, movement_direction.X, 0)
    perp_left = vcVector.new(movement_direction.Y, -movement_direction.X, 0)
    
    # Check which side has more clearance
    right_clearance = check_side_clearance(robot_pos, perp_right, robot_index)
    left_clearance = check_side_clearance(robot_pos, perp_left, robot_index)
    
    # Choose the side with better clearance
    if right_clearance > left_clearance:
        bypass_direction = perp_right
    else:
        bypass_direction = perp_left
    
    # Calculate smooth bypass offset
    bypass_distance = 1200  # Safe bypass distance
    return vector_multiply(bypass_direction, bypass_distance)

def check_side_clearance(position, direction, robot_index):
    """Check how much clearance is available in a given direction"""
    clearance = 5000  # Start with maximum clearance
    
    for other_robot in robots:
        other_robot_index = get_robot_index(other_robot.Name)
        if other_robot_index != robot_index:
            other_pos = getRobotPosition(other_robot)
            
            # Project other robot position onto the direction vector
            relative_pos = vector_subtract(other_pos, position)
            projection = relative_pos.X * direction.X + relative_pos.Y * direction.Y
            
            # If robot is in the direction we're checking
            if projection > 0:
                # Calculate perpendicular distance to the direction line
                perp_distance = abs(relative_pos.X * (-direction.Y) + relative_pos.Y * direction.X)
                
                if perp_distance < 2000:  # Robot affects this direction
                    clearance = min(clearance, projection - 600)  # Account for robot radius
    
    return max(0, clearance)

def get_movement_direction(robot, robot_index):
    """Get robot's movement direction based on current and next locations"""
    current_location = get_robot_property_value('Location', robot_index)
    next_location = get_robot_property_value('NextLocation', robot_index)
    
    if not next_location:
        return None
    
    # Find the next location component
    next_component = app.findComponent(next_location)
    if not next_component:
        return None
    
    robot_pos = getRobotPosition(robot)
    next_pos = next_component.WorldPositionMatrix.P
    
    direction_vector = vector_subtract(next_pos, robot_pos)
    direction_length = vector_length(direction_vector)
    
    if direction_length == 0:
        return None
    
    return normalize_vector(direction_vector)

def get_distance_to_location(robot, location_name):
    """Calculate distance from robot to a specific location"""
    robot_pos = getRobotPosition(robot)
    location_component = app.findComponent(location_name)
    if location_component:
        location_pos = location_component.WorldPositionMatrix.P
        return vector_length(vector_subtract(robot_pos, location_pos))
    return float('inf')

def apply_collision_avoidance_offset(robot, robot_index, other_robot, other_robot_index):
    """Apply dynamic offset to prevent nose-to-nose collision - force side-by-side movement"""
    robot_pos = getRobotPosition(robot)
    other_pos = getRobotPosition(other_robot)
    vehicle = robot_states[robot_index]['vehicle']
    
    if not vehicle:
        return
    
    # Calculate direction vector between robots
    relative_pos = vector_subtract(other_pos, robot_pos)
    distance = vector_length(relative_pos)
    
    if distance == 0:
        return
    
    # Calculate perpendicular offset direction (right side)
    relative_direction = normalize_vector(relative_pos)
    perpendicular_direction = vcVector.new(-relative_direction.Y, relative_direction.X, 0)
    
    # Determine which robot goes to which side based on priority/index
    self_priority = get_robot_property_value('Priority', robot_index)
    other_priority = get_robot_property_value('Priority', other_robot_index)
    
    # Higher priority robot goes right, lower priority goes left
    if self_priority < other_priority or (self_priority == other_priority and robot_index < other_robot_index):
        offset_direction = perpendicular_direction  # Go right
    else:
        offset_direction = vector_multiply(perpendicular_direction, -1)  # Go left
    
    # Apply strong lateral offset to avoid collision
    offset_distance = 1200  # Strong offset to ensure clearance
    lateral_offset = vector_multiply(offset_direction, offset_distance)
    
    # Get current movement direction
    current_direction = get_movement_direction(robot, robot_index)
    if not current_direction:
        return
    
    # Create new path points with lateral offset
    forward_distance = 1500  # Continue forward while offsetting
    offset_target = vector_add(robot_pos, vector_add(vector_multiply(current_direction, forward_distance), lateral_offset))
    
    # Apply the offset path immediately
    vehicle.clearMove()
    vehicle.MaxSpeed = 600  # Slightly slower for offset maneuver
    vehicle.addControlPoint(offset_target)
    
    # Mark robot as using collision avoidance
    robot_states[robot_index]['using_avoidance_offset'] = True

def initiate_backup_maneuver(robot, robot_index):
    """Initiate a backup maneuver to resolve deadlock"""
    robot_pos = getRobotPosition(robot)
    vehicle = robot_states[robot_index]['vehicle'] if robot_index in robot_states else None
    
    if not vehicle:
        return
    
    # Mark robot as backing up
    robot_states[robot_index]['backing_up'] = True
    robot_states[robot_index]['backup_start_time'] = sim.SimTime
    
    # Calculate backup direction (opposite to current facing direction)
    current_matrix = robot.WorldPositionMatrix
    current_direction = normalize_vector(current_matrix.N)
    backup_direction = vector_multiply(current_direction, -1)  # Reverse direction
    
    # Calculate backup position (1000 units backward)
    backup_distance = 1000
    backup_position = vector_add(robot_pos, vector_multiply(backup_direction, backup_distance))
    
    # Clear current movement and set backup movement
    vehicle.clearMove()
    vehicle.MaxSpeed = 400  # Slower speed for backup
    vehicle.addControlPoint(backup_position)
    
    # Reset movement state to allow re-planning after backup
    robot_states[robot_index]['moving'] = False
    robot_states[robot_index]['vehicle_initialized'] = False
    
    # Clear target temporarily to prevent immediate re-planning
    current_target = get_robot_property_value('Target', robot_index)
    robot_states[robot_index]['original_target'] = current_target
    set_robot_property('Target', '', robot_index)

def check_backup_completion(robot, robot_index):
    """Check if backup maneuver is complete and restore normal operation"""
    if 'backing_up' not in robot_states[robot_index] or not robot_states[robot_index]['backing_up']:
        return
    
    current_time = sim.SimTime
    backup_duration = current_time - robot_states[robot_index]['backup_start_time']
    
    # Complete backup after 2 seconds or when robot has moved sufficiently
    if backup_duration > 2.0:
        robot_states[robot_index]['backing_up'] = False
        vehicle = robot_states[robot_index]['vehicle']
        if vehicle:
            vehicle.MaxSpeed = 800  # Restore normal speed
        
        # Restore original target after a brief delay
        if 'original_target' in robot_states[robot_index]:
            set_robot_property('Target', robot_states[robot_index]['original_target'], robot_index)
            del robot_states[robot_index]['original_target']
        
        # Reset stop tracking
        robot_states[robot_index]['stop_start_time'] = 0
        robot_states[robot_index]['consecutive_stop_time'] = 0

def check_proximity(robot, robot_index):
    """Enhanced collision detection with improved head-on collision prevention"""
    current_location = get_robot_property_value('Location', robot_index)
    next_location = get_robot_property_value('NextLocation', robot_index)
    robot_pos = getRobotPosition(robot)
    should_stop = False
    speed_reduction = 1.0  # Default speed multiplier (no reduction)
    
    # Enhanced transition zone awareness
    in_transition_zone = False
    approaching_conveyor = False
    
    # Check if robot is approaching a conveyor (for speed reduction)
    if next_location and is_conveyor(next_location):
        next_component = app.findComponent(next_location)
        if next_component:
            next_pos = next_component.WorldPositionMatrix.P
            distance_to_next = vector_length(vector_subtract(next_pos, robot_pos))
            if distance_to_next < 3000:  # Approaching conveyor within 3000 units
                approaching_conveyor = True
    
    if next_location and robot_index in robot_states and robot_states[robot_index]['moving']:
        pathways = robot_states[robot_index]['pathways']
        current_pathway_index = robot_states[robot_index]['current_pathway_index']
        
        if current_pathway_index < len(pathways) - 1:
            current_pathway = pathways[current_pathway_index]
            next_pathway = pathways[current_pathway_index + 1]
            
            # Check if robot is near transition zone
            if can_transition_directly(robot_pos, current_pathway, next_pathway, 2000):
                in_transition_zone = True

    # Check reservations when approaching pathway boundaries (with transition awareness)
    if next_location and not in_transition_zone:
        next_component = app.findComponent(next_location)
        if next_component:
            next_pos = next_component.WorldPositionMatrix.P
            distance_to_next = vector_length(vector_subtract(next_pos, robot_pos))
            
            if distance_to_next < 1200 and not is_pathway_available(next_location, robot_index):
                should_stop = True

    # ENHANCED head-on collision detection with improved direction analysis
    if not should_stop:
        robot_moving = robot_index in robot_states and robot_states[robot_index]['moving']
        
        # Detection zones with stricter head-on collision handling
        early_detection_zone = 5000   # Increased for better early detection
        coordination_zone = 4000      # Increased for better coordination
        critical_zone = 2500 if not in_transition_zone else 3000  # Larger buffer in transition zones
        
        for other_robot in robots:
            if other_robot != robot:
                other_robot_index = get_robot_index(other_robot.Name)
                other_pos = getRobotPosition(other_robot)
                dist = vector_length(vector_subtract(robot_pos, other_pos))
                other_moving = other_robot_index in robot_states and robot_states[other_robot_index]['moving']
                
                if robot_moving and other_moving and dist < early_detection_zone:
                    # Enhanced direction calculation for better head-on detection
                    self_direction = get_movement_direction(robot, robot_index)
                    other_direction = get_movement_direction(other_robot, other_robot_index)
                    
                    if self_direction and other_direction:
                        # Calculate if robots are approaching each other
                        relative_pos = vector_subtract(other_pos, robot_pos)
                        
                        # Check if this robot is moving toward the other robot
                        self_approaching = (relative_pos.X * self_direction.X + relative_pos.Y * self_direction.Y) > 0
                        
                        # Check if other robot is moving toward this robot
                        other_to_self = vector_subtract(robot_pos, other_pos)
                        other_approaching = (other_to_self.X * other_direction.X + other_to_self.Y * other_direction.Y) > 0
                        
                        # CRITICAL: Enhanced head-on collision detection
                        if self_approaching and other_approaching:
                            # Calculate angle between movement directions (head-on detection)
                            dot_product = self_direction.X * other_direction.X + self_direction.Y * other_direction.Y
                            
                            # If robots are moving in nearly opposite directions (head-on collision)
                            if dot_product < -0.5:  # Strong opposite direction indicator
                                self_priority = get_robot_property_value('Priority', robot_index)
                                other_priority = get_robot_property_value('Priority', other_robot_index)
                                
                                # CRITICAL ZONE - immediate action for head-on collision
                                if dist < critical_zone:
                                    if self_priority >= other_priority:
                                        should_stop = True
                                        robot_states[robot_index]['coordinate_side_by_side'] = True
                                        robot_states[robot_index]['coordination_partner'] = other_robot_index
                                        break
                                
                                # COORDINATION ZONE - start coordinated avoidance
                                elif dist < coordination_zone:
                                    if not robot_states[robot_index].get('in_coordination', False):
                                        robot_states[robot_index]['coordinate_side_by_side'] = True
                                        robot_states[robot_index]['coordination_partner'] = other_robot_index
                                        robot_states[robot_index]['in_coordination'] = True
                                        robot_states[robot_index]['coordination_start_time'] = sim.SimTime
                                        robot_states[robot_index]['vehicle_initialized'] = False
                                        
                                        if other_robot_index in robot_states:
                                            robot_states[other_robot_index]['coordinate_side_by_side'] = True
                                            robot_states[other_robot_index]['coordination_partner'] = robot_index
                                            robot_states[other_robot_index]['in_coordination'] = True
                                            robot_states[other_robot_index]['coordination_start_time'] = sim.SimTime
                                            robot_states[other_robot_index]['vehicle_initialized'] = False
                                    
                                    # Speed reduction for head-on scenarios
                                    speed_reduction = 0.5  # Stronger reduction for head-on collisions
                                
                                # EARLY DETECTION ZONE - early speed reduction for head-on
                                elif dist < early_detection_zone:
                                    if not robot_states[robot_index].get('in_coordination', False):
                                        speed_reduction = 0.7  # Early speed reduction for head-on approach
                            
                            # Regular side-by-side scenarios (not head-on)
                            else:
                                self_priority = get_robot_property_value('Priority', robot_index)
                                other_priority = get_robot_property_value('Priority', other_robot_index)
                                
                                if dist < critical_zone:
                                    if self_priority >= other_priority:
                                        should_stop = True
                                        robot_states[robot_index]['coordinate_side_by_side'] = True
                                        robot_states[robot_index]['coordination_partner'] = other_robot_index
                                        break
                                elif dist < coordination_zone:
                                    if not robot_states[robot_index].get('in_coordination', False):
                                        robot_states[robot_index]['coordinate_side_by_side'] = True
                                        robot_states[robot_index]['coordination_partner'] = other_robot_index
                                        robot_states[robot_index]['in_coordination'] = True
                                        robot_states[robot_index]['coordination_start_time'] = sim.SimTime
                                        robot_states[robot_index]['vehicle_initialized'] = False
                                        
                                        if other_robot_index in robot_states:
                                            robot_states[other_robot_index]['coordinate_side_by_side'] = True
                                            robot_states[other_robot_index]['coordination_partner'] = robot_index
                                            robot_states[other_robot_index]['in_coordination'] = True
                                            robot_states[other_robot_index]['coordination_start_time'] = sim.SimTime
                                            robot_states[other_robot_index]['vehicle_initialized'] = False
                                    
                                    speed_reduction = 0.6
                                elif dist < early_detection_zone:
                                    if not robot_states[robot_index].get('in_coordination', False):
                                        speed_reduction = 0.8
                
                # Handle stationary robots
                elif robot_moving and not other_moving and dist < 2500:
                    self_direction = get_movement_direction(robot, robot_index)
                    if self_direction:
                        relative_pos = vector_subtract(other_pos, robot_pos)
                        approaching = (relative_pos.X * self_direction.X + relative_pos.Y * self_direction.Y) > 0
                        
                        if approaching:
                            if dist < critical_zone:
                                can_bypass = check_bypass_possibility(robot, robot_index, other_robot, other_robot_index)
                                if not can_bypass:
                                    should_stop = True
                                    break
                                else:
                                    robot_states[robot_index]['bypass_target'] = other_robot_index
                            else:
                                # Only reduce speed when approaching conveyors or stationary robots
                                if approaching_conveyor:
                                    speed_reduction = 0.6  # Reduce speed when approaching conveyor
                                else:
                                    speed_reduction = 0.8  # Normal reduction for stationary robots

    # Apply speed reduction to vehicle - only when approaching conveyors or in collision scenarios
    if robot_index in robot_states and robot_states[robot_index]['vehicle']:
        vehicle = robot_states[robot_index]['vehicle']
        base_speed = 800.0
        
        # Apply speed reduction based on situation
        if approaching_conveyor or speed_reduction < 1.0:
            new_speed = base_speed * speed_reduction
            if vehicle.MaxSpeed != new_speed:
                vehicle.MaxSpeed = new_speed
        elif vehicle.MaxSpeed != base_speed:
            # Restore normal speed when not approaching conveyor and no collision risk
            vehicle.MaxSpeed = base_speed
    
    set_robot_property('Stop', should_stop, robot_index)

def reserve_planned_path(robot_index, planned_pathways):
    """Reserve planned path with shorter, more efficient timing"""
    robot_planned_paths[robot_index] = planned_pathways
    reservation_success = True
    
    # Try to reserve all pathways in the planned route
    for i, pathway in enumerate(planned_pathways):
        pathway_name = pathway.Name if hasattr(pathway, 'Name') else pathway
        # Shorter reservation duration to reduce unnecessary blocking
        reservation_duration = 5.0 + (i * 1.0)  # Reduced duration
        
        if not reserve_pathway(pathway_name, robot_index, reservation_duration):
            # Could not reserve pathway - path planning failed
            reservation_success = False
            break
    
    return reservation_success

def release_completed_reservations(robot_index):
    """Release reservations for pathways the robot has passed"""
    if robot_index not in robot_planned_paths:
        return
    
    current_location = get_robot_property_value('Location', robot_index)
    planned_path = robot_planned_paths[robot_index]
    
    # Find current position in planned path
    current_path_index = -1
    for i, pathway in enumerate(planned_path):
        pathway_name = pathway.Name if hasattr(pathway, 'Name') else pathway
        if pathway_name == current_location:
            current_path_index = i
            break
    
    # Release reservations for passed pathways
    if current_path_index > 0:
        for i in range(current_path_index):
            pathway = planned_path[i]
            pathway_name = pathway.Name if hasattr(pathway, 'Name') else pathway
            release_pathway_reservation(pathway_name, robot_index)

def findAnyComponentOnInputConveyor(input_conveyor_name):
    """Find any produced component on the specified input conveyor that is not already attached to another robot"""
    components = app.Components
    
    target_conveyor = app.findComponent(input_conveyor_name)
    if not target_conveyor:
        return None
    
    conveyor_pos = target_conveyor.WorldPositionMatrix.P
    
    closest_component = None
    closest_distance = float('inf')
    
    for c in components:
        if (c.Name != input_conveyor_name and 
            not c.Name.startswith('Mobile Robot') and 
            not c.Name.startswith('Pathway') and 
            not c.Name.startswith('Idle') and
            not c.Name.startswith('_Template') and
            c.Name != 'Component1'):
            
            component_pos = c.WorldPositionMatrix.P
            distance = vector_length(vector_subtract(component_pos, conveyor_pos))
            
            if distance < 8000:
                # Check if component is already attached to another robot
                attached_to_prop = c.getProperty('AttachedToRobot')
                if attached_to_prop and attached_to_prop.Value and attached_to_prop.Value.strip() != '':
                    continue  # Skip components already attached to robots
                
                if distance < closest_distance:
                    closest_distance = distance
                    closest_component = c.Name
    
    return closest_component

def findComponentNameByProductType(product_type):
    components = app.Components
    for c in components:
        product_type_prop = c.getProperty('ProductType')
        if product_type_prop and product_type_prop.Value == product_type:
            return c.Name
    return None

def attachComponentToRobot(component_name, robot):
    component = app.findComponent(component_name)
    if component and robot:
        # Check if component is already attached to another robot (not this robot)
        attached_to_prop = component.getProperty('AttachedToRobot')
        if attached_to_prop and attached_to_prop.Value and attached_to_prop.Value.strip() != '' and attached_to_prop.Value != robot.Name:
            # Component is already attached to a different robot, skip
            return False
        
        # Create or update the AttachedToRobot property
        if not attached_to_prop:
            attached_to_prop = component.createProperty(VC_STRING, 'AttachedToRobot')
        attached_to_prop.Value = robot.Name
        
        # Create a custom property to store the component's original position
        original_pos_prop = component.getProperty('OriginalPosition')
        if not original_pos_prop:
            original_pos_prop = component.createProperty(VC_STRING, 'OriginalPosition')
        
        # Store current position as original position
        current_pos = component.WorldPositionMatrix.P
        original_pos_prop.Value = "{},{},{}".format(current_pos.X, current_pos.Y, current_pos.Z)
        
        # IMMEDIATELY position the component behind the robot (no attachment, just positioning)
        robot_pos = robot.WorldPositionMatrix.P
        offset_matrix = mat.new()
        offset_matrix.translateAbs(robot_pos.X - 600, robot_pos.Y, robot_pos.Z + 200)
        component.PositionMatrix = offset_matrix
        component.rebuild()
        
        return True
    return False

def relocateComponentOnConveyor(component_name, conveyor, robot):
    component = app.findComponent(component_name)
    if component:
        # No need to detach from root feature since we're not using root feature attachment
        
        # Clear the AttachedToRobot property when detaching
        attached_to_prop = component.getProperty('AttachedToRobot')
        if attached_to_prop:
            attached_to_prop.Value = ''
        
        # Position component on the conveyor
        conveyor_pos = conveyor.WorldPositionMatrix
        conveyor_height = conveyor.getProperty('ConveyorHeight').Value if conveyor.getProperty('ConveyorHeight') else 0

        new_pos = mat.new()
        new_pos.translateAbs(conveyor_pos.P.X, conveyor_pos.P.Y, conveyor_pos.P.Z + conveyor_height)
        
        component.PositionMatrix = new_pos
        component.rebuild()

def updateCarriedComponentPosition(robot, robot_index):
    """Update the position of any component carried by this robot"""
    carried_product_name = get_robot_property_value('CarriedProduct', robot_index)
    if carried_product_name and carried_product_name.strip() != '':
        component = app.findComponent(carried_product_name)
        if component:
            # Check if this component is actually attached to this robot
            attached_to_prop = component.getProperty('AttachedToRobot')
            if attached_to_prop and attached_to_prop.Value == robot.Name:
                # Update component position to follow the robot
                robot_matrix = robot.WorldPositionMatrix
                
                # Create offset position (600 units behind robot, 200 units up)
                offset_matrix = mat.new()
                # Use robot's orientation to calculate proper offset
                robot_direction = normalize_vector(robot_matrix.N)
                offset_pos = vector_subtract(robot_matrix.P, vector_multiply(robot_direction, 600))
                offset_pos = vector_add(offset_pos, vcVector.new(0, 0, 200))
                
                # Set position and copy robot's orientation
                offset_matrix.translateAbs(offset_pos.X, offset_pos.Y, offset_pos.Z)
                # Copy robot's direction vector
                offset_matrix.N = robot_matrix.N
                
                component.PositionMatrix = offset_matrix

def distance(p1, p2):
    return ((p1['X'] - p2['X'])**2 + (p1['Y'] - p2['Y'])**2)**0.5

def reserve_pathway(pathway_name, robot_index, duration=3.0):
    """Reserve a pathway for a robot - shorter duration to reduce blocking"""
    current_time = sim.SimTime
    if pathway_name not in pathway_reservations:
        pathway_reservations[pathway_name] = {}
    
    # Check if pathway is available
    for reserved_robot, expiry_time in list(pathway_reservations[pathway_name].items()):
        if expiry_time > current_time and reserved_robot != robot_index:
            return False  # Pathway is reserved by another robot
        elif expiry_time <= current_time:
            # Remove expired reservations
            del pathway_reservations[pathway_name][reserved_robot]
    
    # Reserve the pathway with shorter duration
    pathway_reservations[pathway_name][robot_index] = current_time + duration
    return True

def release_pathway_reservation(pathway_name, robot_index):
    """Release a pathway reservation when robot moves to next segment"""
    if pathway_name in pathway_reservations and robot_index in pathway_reservations[pathway_name]:
        del pathway_reservations[pathway_name][robot_index]

def is_pathway_available(pathway_name, robot_index):
    """Check if pathway is available for reservation"""
    current_time = sim.SimTime
    if pathway_name not in pathway_reservations:
        return True
    
    for reserved_robot, expiry_time in list(pathway_reservations[pathway_name].items()):
        if expiry_time > current_time and reserved_robot != robot_index:
            return False
        elif expiry_time <= current_time:
            # Clean up expired reservations
            del pathway_reservations[pathway_name][reserved_robot]
    
    return True

def find_shortest_path_with_reservations(start, goal, pathways, robot_index):
    """Enhanced pathfinding with collision-aware reservation system and conflict prediction"""
    def heuristic(a, b):
        return distance(a, b)

    def get_neighbors(current):
        neighbors = []
        for p in pathways:
            if p != current and distance(current, p) <= 12000:
                # Check if pathway can be reserved
                if is_pathway_available(p['Name'], robot_index):
                    # PROACTIVE: Also check if other robots are planning to use this pathway
                    conflict_predicted = False
                    
                    # Check if other robots have this pathway in their planned paths
                    for other_robot_index, planned_path in robot_planned_paths.items():
                        if other_robot_index != robot_index:
                            for planned_pathway in planned_path:
                                planned_name = planned_pathway.Name if hasattr(planned_pathway, 'Name') else planned_pathway
                                if planned_name == p['Name']:
                                    # Check timing - if other robot will be here soon, avoid
                                    other_robot = next((r for r in robots if get_robot_index(r.Name) == other_robot_index), None)
                                    if other_robot:
                                        other_pos = getRobotPosition(other_robot)
                                        pathway_pos = {'X': p['X'], 'Y': p['Y']}
                                        distance_to_pathway = distance(
                                            {'X': other_pos.X, 'Y': other_pos.Y}, 
                                            pathway_pos
                                        )
                                        # If other robot is close to this pathway, avoid conflict
                                        if distance_to_pathway < 2000:
                                            conflict_predicted = True
                                            break
                    
                    if not conflict_predicted:
                        neighbors.append(p)
        return neighbors

    def calculate_path_cost(current, neighbor):
        """Calculate cost with collision avoidance factors"""
        base_cost = distance(current, neighbor)
        
        # Add penalty for pathways with high robot density
        density_penalty = 0
        neighbor_pos = {'X': neighbor['X'], 'Y': neighbor['Y']}
        
        for robot in robots:
            robot_pos = getRobotPosition(robot)
            robot_dict_pos = {'X': robot_pos.X, 'Y': robot_pos.Y}
            if distance(neighbor_pos, robot_dict_pos) < 2000:
                density_penalty += 500  # Penalty for crowded areas
        
        # Add penalty for pathways that other robots are targeting
        target_penalty = 0
        for other_robot in robots:
            other_robot_index = get_robot_index(other_robot.Name)
            if other_robot_index != robot_index:
                other_next_location = get_robot_property_value('NextLocation', other_robot_index)
                if other_next_location == neighbor['Name']:
                    target_penalty += 1000  # High penalty for contested pathways
        
        return base_cost + density_penalty + target_penalty

    open_set = []
    heapq.heappush(open_set, (0, start))
    came_from = {}
    g_score = {start['Name']: 0}
    f_score = {start['Name']: heuristic(start, goal)}

    while open_set:
        current = heapq.heappop(open_set)[1]

        if current['Name'] == goal['Name']:
            path = []
            while current['Name'] in came_from:
                path.append(current)
                current = came_from[current['Name']]
            path.append(start)
            return path[::-1]

        for neighbor in get_neighbors(current):
            tentative_g_score = g_score[current['Name']] + calculate_path_cost(current, neighbor)

            if neighbor['Name'] not in g_score or tentative_g_score < g_score[neighbor['Name']]:
                came_from[neighbor['Name']] = current
                g_score[neighbor['Name']] = tentative_g_score
                f_score[neighbor['Name']] = g_score[neighbor['Name']] + heuristic(neighbor, goal)
                heapq.heappush(open_set, (f_score[neighbor['Name']], neighbor))

    return None

def OnRun():
    global robots, robot_states, comp, app, sim

    # Wait for RobotQuantity to be set
    for i in range(50):  # 5 seconds max
        robot_quantity_prop = comp.getProperty('RobotQuantity')
        if robot_quantity_prop and robot_quantity_prop.Value > 0:
            clone_robots()
            break
        delay(0.1)
    
    if not robots:
        return

    delay(2)

    # Fetch all pathways from the 3D world
    all_components = app.Components
    pathways = [component for component in all_components if component.Name.startswith('Pathway Area') or component.Name.startswith('Idle Location')]
    pathways_dict = []
    for p in pathways:
        length1 = p.getProperty('Length1').Value if p.getProperty('Length1') else 0
        length2 = p.getProperty('Length2').Value if p.getProperty('Length2') else 0
        width1 = p.getProperty('Width1').Value if p.getProperty('Width1') else 0
        width2 = p.getProperty('Width2').Value if p.getProperty('Width2') else 0
        area_length = (length1 + length2) / 2 if (length1 or length2) else 500
        area_width = (width1 + width2) / 2 if (width1 or width2) else 500
        pathways_dict.append({
            "Name": p.Name,
            "X": p.WorldPositionMatrix.P.X,
            "Y": p.WorldPositionMatrix.P.Y,
            "Rz": math.degrees(math.atan2(p.WorldPositionMatrix.N.Y, p.WorldPositionMatrix.N.X)),
            "AreaLength": area_length,
            "AreaWidth": area_width
        })

    # Dynamically find all conveyor components by checking if their names contain 'conveyor' (case-insensitive)
    conveyor_components = [comp.Name for comp in app.Components if 'conveyor' in comp.Name.lower()]
    conveyors = {name: app.findComponent(name) for name in conveyor_components}
    


    # Initialize per-robot states
    robot_states = {}
    for robot in robots:
        robot_index = get_robot_index(robot.Name)
        robot_states[robot_index] = {
            'current_pathway_index': 0,
            'vehicle': robot.findBehaviour("Vehicle"),
            'moving': False,
            'vehicle_initialized': False,
            'elapsed_time': 0.0,
            'total_move_time': 0.0,
            'pathways': [],
            'conveyor_destination': None,
            'using_avoidance_offset': False
        }

    while True:
        for robot in robots:
            robot_index = get_robot_index(robot.Name)
            vehicle = robot_states[robot_index]['vehicle']

            if not robot_states[robot_index]['moving']:
                robot_pos = getRobotPosition(robot)
                start_pathway = {
                    "Name": "Robot Start",
                    "X": robot_pos.X,
                    "Y": robot_pos.Y,
                    "Rz": 0,
                    "AreaLength": 0,
                    "AreaWidth": 0
                }

                goal_pathway_name = get_robot_property_value('Target', robot_index)

                if not goal_pathway_name:
                    continue
                


                if goal_pathway_name in conveyor_components:
                    conveyor = conveyors[goal_pathway_name]
                    if conveyor:
                        goal_pathway = {
                            "Name": goal_pathway_name,
                            "X": conveyor.WorldPositionMatrix.P.X,
                            "Y": conveyor.WorldPositionMatrix.P.Y,
                            "Rz": 0,
                            "AreaLength": conveyor.getProperty('ConveyorLength').Value if conveyor.getProperty('ConveyorLength') else 0,
                            "AreaWidth": conveyor.getProperty('ConveyorWidth').Value if conveyor.getProperty('ConveyorWidth') else 0
                        }
                    else:
                        continue
                else:
                    goal_pathway = next((p for p in pathways_dict if p['Name'] == goal_pathway_name), None)

                if not goal_pathway:
                    continue

                # Use reservation-based pathfinding
                shortest_path = find_shortest_path_with_reservations(start_pathway, goal_pathway, pathways_dict + [goal_pathway], robot_index)

                if shortest_path:
                    # Separate pathways from conveyors - conveyors are destinations, not pathways to traverse
                    pathways_robot = [app.findComponent(p['Name']) for p in shortest_path[1:] if p['Name'] not in conveyor_components]
                    conveyor_destination = None
                    
                    if goal_pathway_name in conveyor_components:
                        conveyor_destination = conveyors[goal_pathway_name]

                    # Try to reserve the pathway portion only (excluding conveyor destination)
                    if reserve_planned_path(robot_index, pathways_robot):
                        # Clear location when starting new journey (robot is no longer "at" previous conveyor)
                        set_robot_property('Location', '', robot_index)
                        set_robot_property('NextLocation', '', robot_index)
                        
                        robot_states[robot_index]['pathways'] = pathways_robot
                        robot_states[robot_index]['conveyor_destination'] = conveyor_destination
                        robot_states[robot_index]['current_pathway_index'] = 0
                        robot_states[robot_index]['moving'] = True
                        robot_states[robot_index]['vehicle_initialized'] = False  # Reset vehicle for new journey
                    else:
                        # Path reservation failed - robot will wait and try again
                        set_robot_property('Stop', True, robot_index)
                        # Clear any previous conveyor destination
                        robot_states[robot_index]['conveyor_destination'] = None
                else:
                    # No path found - robot will wait
                    set_robot_property('Stop', True, robot_index)
                    # Clear any previous conveyor destination
                    robot_states[robot_index]['conveyor_destination'] = None

            move_robot_incremental(robot, vehicle, robot_index, robot_states[robot_index])
            
            # Update carried component position to follow the robot
            updateCarriedComponentPosition(robot, robot_index)
            
            # Release reservations for completed pathway segments
            release_completed_reservations(robot_index)

            # Handle pickup and drop-off with proximity-based detection
            goal_pathway_name = get_robot_property_value('Target', robot_index)
            robot_pos = getRobotPosition(robot)
            
            # Only handle conveyor interactions when robot has reached conveyor destination
            # (not while still moving through pathways)
            robot_moving = robot_states[robot_index]['moving']
            conveyor_destination = robot_states[robot_index].get('conveyor_destination')
            current_location = get_robot_property_value('Location', robot_index)
            
            # Update robot location when near target conveyors for OPC-UA (immediate update)
            if goal_pathway_name and goal_pathway_name in conveyor_components:
                target_conveyor = conveyors.get(goal_pathway_name)
                if target_conveyor:
                    conveyor_pos = target_conveyor.WorldPositionMatrix.P
                    distance_to_conveyor = vector_length(vector_subtract(robot_pos, conveyor_pos))
                    
                    # Set location when robot is close to conveyor (for OPC-UA monitoring)
                    if distance_to_conveyor < 1500:
                        if current_location != goal_pathway_name:
                            set_robot_property('Location', goal_pathway_name, robot_index)
                            set_robot_property('NextLocation', '', robot_index)

            # Only handle component pickup/drop-off when robot is at conveyor destination (not moving)
            if not robot_moving and current_location in conveyor_components:
                if is_input_conveyor(goal_pathway_name):
                    # Check if robot is at the target conveyor (for pickup)
                    target_conveyor = conveyors.get(goal_pathway_name)
                    carried_product = get_robot_property_value('CarriedProduct', robot_index)
                    
                    # Check if robot is not already carrying something and is at the right conveyor
                    if target_conveyor and (not carried_product or carried_product == '') and current_location == goal_pathway_name:
                        conveyor_pos = target_conveyor.WorldPositionMatrix.P
                        distance_to_conveyor = vector_length(vector_subtract(robot_pos, conveyor_pos))
                        
                        if distance_to_conveyor < 2000:  # Within pickup range at destination
                            # Find any produced component on this input conveyor, regardless of product type
                            component_name = findAnyComponentOnInputConveyor(goal_pathway_name)
                            if component_name:
                                # Double-check that no other robot is currently carrying this component
                                component = app.findComponent(component_name)
                                if component:
                                    attached_to_prop = component.getProperty('AttachedToRobot')
                                    if not attached_to_prop or not attached_to_prop.Value or attached_to_prop.Value.strip() == '':
                                        # Try to attach the component
                                        if attachComponentToRobot(component_name, robot):
                                            # Don't set CarryingProduct here - let OPC-UA handle it
                                            set_robot_property('CarriedProduct', component_name, robot_index)
                                
                elif is_output_conveyor(goal_pathway_name):
                    # Check if robot is at the target conveyor (for drop-off)
                    target_conveyor = conveyors.get(goal_pathway_name)
                    
                    # Check if robot should drop off and is at the right conveyor
                    carrying_product = get_robot_property_value('CarryingProduct', robot_index)
                    carried_product_name = get_robot_property_value('CarriedProduct', robot_index)
                    is_actually_carrying = carrying_product or (carried_product_name and carried_product_name != '')
                    
                    if target_conveyor and is_actually_carrying and current_location == goal_pathway_name:
                        conveyor_pos = target_conveyor.WorldPositionMatrix.P
                        distance_to_conveyor = vector_length(vector_subtract(robot_pos, conveyor_pos))
                        
                        if distance_to_conveyor < 2000:  # Within drop-off range at destination
                            carried_product = get_robot_property_value('CarriedProduct', robot_index)
                            if carried_product and carried_product.strip() != '':
                                relocateComponentOnConveyor(carried_product, target_conveyor, robot)
                                # Don't set CarryingProduct to False here - let OPC-UA handle it
                                set_robot_property('CarriedProduct', '', robot_index)
                                set_robot_property('Target', '', robot_index)
                                # Don't clear location immediately - keep robot "at conveyor" for OPC-UA monitoring
                                # Location will be cleared when robot gets new target and starts moving
                                set_robot_property('NextLocation', '', robot_index)
                


        delay(0.1)

def move_robot_incremental(robot, vehicle, robot_index, robot_state):
    """Smooth robot movement - prevents teleporting and freezing"""
    if not robot_state['moving'] or not vehicle:
        return

    pathways = robot_state['pathways']
    i = robot_state['current_pathway_index']

    if i >= len(pathways):
        # Robot has completed all pathways - now handle conveyor destination if exists
        conveyor_destination = robot_state.get('conveyor_destination')
        if conveyor_destination:
            # Move to conveyor destination
            conveyor_pos = conveyor_destination.WorldPositionMatrix.P
            robot_pos = getRobotPosition(robot)
            distance_to_conveyor = vector_length(vector_subtract(robot_pos, conveyor_pos))
            
            # Check if robot is close enough to the conveyor
            if distance_to_conveyor < 1500:  # Within conveyor reach
                # Robot has reached conveyor destination
                robot_state['moving'] = False
                # Immediately set location for OPC-UA system
                set_robot_property('Location', conveyor_destination.Name, robot_index)
                set_robot_property('NextLocation', '', robot_index)
                robot_state['conveyor_destination'] = None  # Clear destination
                # Release all remaining reservations
                if robot_index in robot_planned_paths:
                    for pathway in robot_planned_paths[robot_index]:
                        pathway_name = pathway.Name if hasattr(pathway, 'Name') else pathway
                        release_pathway_reservation(pathway_name, robot_index)
                    del robot_planned_paths[robot_index]
            else:
                # Continue moving toward conveyor destination WITH collision avoidance
                if not robot_state.get('vehicle_initialized', False):
                    if vehicle:
                        target_pos = conveyor_pos
                        
                        # Apply collision avoidance when moving to conveyor destination
                        avoidance_offset = calculate_avoidance_offset(robot, robot_index)
                        adjusted_target = vector_add(target_pos, avoidance_offset)
                        
                        # Use the correct vehicle movement method
                        vehicle.clearMove()
                        vehicle.MaxSpeed = 800
                        vehicle.addControlPoint(adjusted_target)
                        robot_state['vehicle_initialized'] = True
                
                # Periodically recalculate collision avoidance for conveyor movement
                if not hasattr(robot_state, 'last_avoidance_update'):
                    robot_state['last_avoidance_update'] = 0
                
                current_time = sim.SimTime if sim else 0
                if current_time - robot_state['last_avoidance_update'] > 1.0:  # Update every 1 second
                    robot_state['vehicle_initialized'] = False  # Force recalculation with current avoidance
                    robot_state['last_avoidance_update'] = current_time
                
                # Check if robot is stuck - if so, re-initialize vehicle
                # Simple check: if robot is far from destination and hasn't moved recently
                if distance_to_conveyor > 200:
                    # Store previous position to check if robot is stuck
                    if not hasattr(robot_state, 'prev_pos'):
                        robot_state['prev_pos'] = robot_pos
                        robot_state['stuck_timer'] = 0
                    else:
                        # Check if robot has moved
                        prev_distance = vector_length(vector_subtract(robot_pos, robot_state['prev_pos']))
                        if prev_distance < 50:  # Robot hasn't moved much
                            robot_state['stuck_timer'] = robot_state.get('stuck_timer', 0) + 0.1
                            if robot_state['stuck_timer'] > 2.0:  # Stuck for 2 seconds
                                robot_state['vehicle_initialized'] = False
                                robot_state['stuck_timer'] = 0
                        else:
                            robot_state['stuck_timer'] = 0
                        robot_state['prev_pos'] = robot_pos
        else:
            # No conveyor destination - robot has finished its journey
            robot_state['moving'] = False
            set_robot_property('Location', '', robot_index)
            set_robot_property('NextLocation', '', robot_index)
            # Release all remaining reservations
            if robot_index in robot_planned_paths:
                for pathway in robot_planned_paths[robot_index]:
                    pathway_name = pathway.Name if hasattr(pathway, 'Name') else pathway
                    release_pathway_reservation(pathway_name, robot_index)
                del robot_planned_paths[robot_index]
        return

    current_pathway = pathways[i]
    robot_pos = getRobotPosition(robot)

    def is_point_in_pathway(point, pathway):
        pos = pathway.WorldPositionMatrix.P
        N = pathway.WorldPositionMatrix.N
        direction = normalize_vector(N)
        
        length1 = pathway.getProperty('Length1').Value if pathway.getProperty('Length1') else 0
        length2 = pathway.getProperty('Length2').Value if pathway.getProperty('Length2') else 0
        width1 = pathway.getProperty('Width1').Value if pathway.getProperty('Width1') else 0
        width2 = pathway.getProperty('Width2').Value if pathway.getProperty('Width2') else 0
        
        if is_conveyor(pathway.Name):
            conveyor_length = pathway.getProperty('ConveyorLength').Value if pathway.getProperty('ConveyorLength') else 0
            length1 = length2 = conveyor_length / 2
            width1 = width2 = pathway.getProperty('ConveyorWidth').Value if pathway.getProperty('ConveyorWidth') else 0

        start_point = vector_subtract(pos, vector_multiply(direction, length1))
        end_point = vector_add(pos, vector_multiply(direction, length2))
        
        perp_direction = vcVector.new(-direction.Y, direction.X, 0)
        
        rel_vector = vector_subtract(point, start_point)
        along_path = vector_length(vcVector.new(
            rel_vector.X * direction.X + rel_vector.Y * direction.Y,
            0,
            0
        ))
        across_path = abs(vector_length(vcVector.new(
            rel_vector.X * perp_direction.X + rel_vector.Y * perp_direction.Y,
            0,
            0
        )))
        
        total_length = vector_length(vector_subtract(end_point, start_point))
        max_width = max(width1, width2)
        
        return 0 <= along_path <= total_length and across_path <= max_width/2

    current_in_pathway = is_point_in_pathway(robot_pos, current_pathway)  
    next_in_pathway = False
    
    # FIXED: Different logic for pathway-to-pathway vs pathway-to-conveyor transitions
    can_transition_direct = False
    if i + 1 < len(pathways):
        next_pathway = pathways[i + 1]
        next_in_pathway = is_point_in_pathway(robot_pos, next_pathway)
        
        # For pathway-to-pathway transitions, use smart exit points
        # IMPORTANT: Only transition if robot is near the EXIT point of current pathway
        curr_pos = current_pathway.WorldPositionMatrix.P
        curr_length1 = current_pathway.getProperty('Length1').Value if current_pathway.getProperty('Length1') else 0
        curr_length2 = current_pathway.getProperty('Length2').Value if current_pathway.getProperty('Length2') else 0
        
        if is_conveyor(current_pathway.Name):
            conveyor_length = current_pathway.getProperty('ConveyorLength').Value if current_pathway.getProperty('ConveyorLength') else 0
            curr_length1 = curr_length2 = conveyor_length / 2

        curr_N = current_pathway.WorldPositionMatrix.N
        curr_direction = normalize_vector(curr_N)
        
        # Calculate the planned exit point for this pathway
        planned_exit_point, _ = calculate_smart_entry_exit_points(robot_pos, current_pathway, next_pathway)
        
        # Only allow transition if robot is VERY close to the planned exit point
        distance_to_exit = vector_length(vector_subtract(robot_pos, planned_exit_point))
        
        # Strict transition criteria - robot must be at the exit point
        if distance_to_exit < 800:  # Must be within 800 units of exit point
            if next_in_pathway:
                can_transition_direct = True
            else:
                # Check if robot can transition directly to next pathway
                can_transition_direct = can_transition_directly(robot_pos, current_pathway, next_pathway, 1200)
        
        # ONLY transition if robot has reached the proper exit point
        if can_transition_direct and distance_to_exit < 800:
            set_robot_property('Location', next_pathway.Name, robot_index)
            if i + 2 < len(pathways):
                set_robot_property('NextLocation', pathways[i + 2].Name, robot_index)
            else:
                # Check if there's a conveyor destination
                conveyor_destination = robot_state.get('conveyor_destination')
                if conveyor_destination:
                    set_robot_property('NextLocation', conveyor_destination.Name, robot_index)
                else:
                    set_robot_property('NextLocation', '', robot_index)
            robot_state['current_pathway_index'] += 1
            robot_state['vehicle_initialized'] = False
            return
    else:
        # CRITICAL FIX: When this is the LAST pathway before conveyor destination
        # Robot should complete the ENTIRE pathway, not try to exit early toward conveyor
        
        # Check if we have a conveyor destination (meaning this is the final pathway)
        conveyor_destination = robot_state.get('conveyor_destination')
        if conveyor_destination:
            # This is the last pathway before going to conveyor
            # Robot must traverse the COMPLETE pathway before transitioning to conveyor
            
            # Calculate if robot has reached the END of the current pathway
            curr_pos = current_pathway.WorldPositionMatrix.P
            curr_length1 = current_pathway.getProperty('Length1').Value if current_pathway.getProperty('Length1') else 0
            curr_length2 = current_pathway.getProperty('Length2').Value if current_pathway.getProperty('Length2') else 0
            
            if is_conveyor(current_pathway.Name):
                conveyor_length = current_pathway.getProperty('ConveyorLength').Value if current_pathway.getProperty('ConveyorLength') else 0
                curr_length1 = curr_length2 = conveyor_length / 2

            curr_N = current_pathway.WorldPositionMatrix.N
            curr_direction = normalize_vector(curr_N)
            
            # Calculate both ends of the pathway
            start_point = vector_subtract(curr_pos, vector_multiply(curr_direction, curr_length1))
            end_point = vector_add(curr_pos, vector_multiply(curr_direction, curr_length2))
            
            # Check distance to both ends to see which one robot should reach
            distance_to_start = vector_length(vector_subtract(robot_pos, start_point))
            distance_to_end = vector_length(vector_subtract(robot_pos, end_point))
            
            # Determine which end the robot should reach based on conveyor position
            conveyor_pos = conveyor_destination.WorldPositionMatrix.P
            distance_start_to_conveyor = vector_length(vector_subtract(conveyor_pos, start_point))
            distance_end_to_conveyor = vector_length(vector_subtract(conveyor_pos, end_point))
            
            # Robot should reach the end that's closer to the conveyor
            target_end = start_point if distance_start_to_conveyor < distance_end_to_conveyor else end_point
            distance_to_target_end = vector_length(vector_subtract(robot_pos, target_end))
            
            # Only transition to conveyor mode when robot has reached the appropriate end
            if distance_to_target_end < 600:  # Within 600 units of pathway end
                # Robot has completed the pathway - now can move to conveyor
                robot_state['current_pathway_index'] = len(pathways)  # Mark pathways as complete
                robot_state['vehicle_initialized'] = False  # Re-initialize for conveyor movement
                set_robot_property('NextLocation', conveyor_destination.Name, robot_index)
                return

    # Smooth stopping logic - prevents freezing with enhanced coordination response
    should_stop = get_robot_property_value('Stop', robot_index)
    
    # Track consecutive stops to prevent permanent freezing
    if 'consecutive_stops' not in robot_state:
        robot_state['consecutive_stops'] = 0
        robot_state['stop_start_time'] = 0
    
    current_time = sim.SimTime
    
    if should_stop:
        if robot_state['stop_start_time'] == 0:
            robot_state['stop_start_time'] = current_time
        
        stop_duration = current_time - robot_state['stop_start_time']
        
        # Enhanced response for coordinated side-by-side movement
        if stop_duration > 0.2:  # Even quicker response - 0.2 seconds
            # If marked for coordination, start side-by-side movement immediately
            if robot_state.get('coordinate_side_by_side', False):
                # Don't clear the flag here - let movement logic handle it
                robot_state['stop_start_time'] = 0
                robot_state['vehicle_initialized'] = False  # Force re-initialization with offset
                should_stop = False  # Allow coordinated movement
            elif stop_duration > 0.8:  # Reduced from 1.0 second - faster recovery
                # Force movement to prevent permanent freezing
                should_stop = False
                robot_state['stop_start_time'] = 0
                robot_state['consecutive_stops'] = 0
            
        # Apply stop with speed reduction instead of complete halt for coordination scenarios
        if should_stop and vehicle:
            # For coordination scenarios, use very slow movement instead of complete stop
            if robot_state.get('coordinate_side_by_side', False) or robot_state.get('in_coordination', False):
                vehicle.MaxSpeed = 200  # Slow movement for coordination
            else:
                vehicle.clearMove()
                vehicle.MaxSpeed = 50  # Very slow speed instead of complete stop
            robot_state['vehicle_initialized'] = False
            return
    else:
        robot_state['stop_start_time'] = 0
        robot_state['consecutive_stops'] = 0
        # Resume normal speed when clear (will be adjusted by check_proximity if needed)
        if vehicle and vehicle.MaxSpeed < 400:  # Only restore if currently very slow
            vehicle.MaxSpeed = 800.0

    # Initialize vehicle movement for current pathway with smart pathfinding
    if not robot_state['vehicle_initialized']:
        robot_pos = getRobotPosition(robot)
        
        # Get next and previous pathways for context
        next_pathway = pathways[i + 1] if i < len(pathways) - 1 else None
        previous_pathway = pathways[i - 1] if i > 0 else None
        
        # CRITICAL FIX: Special handling when this is the last pathway before conveyor
        conveyor_destination = robot_state.get('conveyor_destination')
        is_last_pathway_before_conveyor = (next_pathway is None and conveyor_destination is not None)
        
        if is_last_pathway_before_conveyor:
            # This is the LAST pathway before going to conveyor
            # Robot must complete the ENTIRE pathway using traditional traversal
            # Do NOT optimize toward conveyor - complete the pathway first!
            
            pos = current_pathway.WorldPositionMatrix.P
            length1 = current_pathway.getProperty('Length1').Value if current_pathway.getProperty('Length1') else 0
            length2 = current_pathway.getProperty('Length2').Value if current_pathway.getProperty('Length2') else 0

            if is_conveyor(current_pathway.Name):
                conveyor_length = current_pathway.getProperty('ConveyorLength').Value if current_pathway.getProperty('ConveyorLength') else 0
                length1 = length2 = conveyor_length / 2

            N = current_pathway.WorldPositionMatrix.N
            direction_vector = normalize_vector(N)

            start_point = vector_subtract(pos, vector_multiply(direction_vector, length1))
            end_point = vector_add(pos, vector_multiply(direction_vector, length2))
            
            # Determine which end is closer to the conveyor destination
            conveyor_pos = conveyor_destination.WorldPositionMatrix.P
            distance_start_to_conveyor = vector_length(vector_subtract(conveyor_pos, start_point))
            distance_end_to_conveyor = vector_length(vector_subtract(conveyor_pos, end_point))
            
            # Target the end that's closer to conveyor
            target_end = start_point if distance_start_to_conveyor < distance_end_to_conveyor else end_point
            
            # Create pathway traversal points to ensure robot travels the full pathway - OPTIMIZED
            num_points = 5  # Reduced from 8 to 5 for better performance
            pathway_points = []
            for j in range(num_points + 1):
                t = j / num_points
                interp_vector = vector_add(start_point, vector_multiply(vector_subtract(end_point, start_point), t))
                pathway_points.append(interp_vector)

            # Find closest point to robot's current position
            closest_point = min(pathway_points, key=lambda p: vector_length(vector_subtract(p, robot_pos)))
            closest_index = pathway_points.index(closest_point)
            
            # Create control points to traverse toward the target end
            if target_end == end_point:
                # Go toward end - use points from closest to end
                control_points = pathway_points[closest_index:]
            else:
                # Go toward start - use points from closest to start (reversed)
                control_points = list(reversed(pathway_points[:closest_index + 1]))
            
            # Ensure we have the target end as final point
            if control_points and vector_length(vector_subtract(control_points[-1], target_end)) > 100:
                control_points.append(target_end)
            elif not control_points:
                control_points = [target_end]
                
        else:
            # Normal pathway-to-pathway transition or single pathway
            # Use intelligent pathway points calculation
            entry_point, intermediate_points, exit_point = calculate_intelligent_pathway_points(
                robot_pos, current_pathway, next_pathway, previous_pathway)
            
            # Check if robot is already inside the pathway
            current_in_pathway = is_point_in_pathway(robot_pos, current_pathway)
            
            if current_in_pathway:
                # Robot is already in pathway, create optimal path to exit
                control_points = []
                
                # Add intermediate turning points if beneficial
                if intermediate_points:
                    # Check if intermediate points provide a shorter or smoother path
                    for intermediate in intermediate_points:
                        distance_via_intermediate = (vector_length(vector_subtract(intermediate, robot_pos)) + 
                                                   vector_length(vector_subtract(exit_point, intermediate)))
                        direct_distance = vector_length(vector_subtract(exit_point, robot_pos))
                        
                        # Use intermediate point if it's not much longer and provides better navigation
                        if distance_via_intermediate < direct_distance * 1.2:  # Allow 20% longer path for smoother movement
                            control_points.append(intermediate)
                
                control_points.append(exit_point)
                
            else:
                # Robot needs to enter pathway first
                distance_to_entry = vector_length(vector_subtract(robot_pos, entry_point))
                distance_to_exit = vector_length(vector_subtract(robot_pos, exit_point))
                
                # Smart entry decision
                if distance_to_exit < distance_to_entry * 0.7:  # If much closer to exit, go directly
                    control_points = [exit_point]
                else:
                    # Create efficient path: entry -> (intermediate) -> exit
                    control_points = [entry_point]
                    control_points.extend(intermediate_points)
                    control_points.append(exit_point)
        
        # Remove redundant points that are too close to each other - OPTIMIZED
        filtered_points = []
        min_distance_between_points = 600  # Increased from 400 to reduce point density
        
        for point in control_points:
            if not filtered_points:
                filtered_points.append(point)
            else:
                distance_to_last = vector_length(vector_subtract(point, filtered_points[-1]))
                if distance_to_last > min_distance_between_points:
                    filtered_points.append(point)
        
        # Ensure we have at least one control point
        control_points = filtered_points if filtered_points else [exit_point]
        
        # OPTIMIZED: Simplified path optimization (less computation)
        if len(control_points) > 3:  # Only optimize if we have more than 3 points
            optimized_points = [control_points[0]]  # Always keep first point
            
            # Keep every other middle point for simpler optimization
            for j in range(2, len(control_points) - 1, 2):  # Skip every other point
                optimized_points.append(control_points[j])
            
            optimized_points.append(control_points[-1])  # Always keep last point
            control_points = optimized_points

        # Handle coordinated side-by-side movement - STABLE ASSIGNMENT (no zigzag)
        lateral_offset = vcVector.new(0, 0, 0)
        
        # Check if robot is in coordinated side-by-side mode
        if robot_index in robot_states and robot_states[robot_index].get('coordinate_side_by_side', False):
            partner_index = robot_states[robot_index].get('coordination_partner')
            if partner_index:
                # Calculate coordinated side-by-side offset ONCE
                lateral_offset = calculate_coordinated_side_by_side_offset(robot, robot_index, current_pathway, partner_index)
                
                # Store the stable offset to prevent recalculation
                robot_states[robot_index]['stable_offset'] = lateral_offset
                robot_states[robot_index]['coordinate_side_by_side'] = False  # Clear flag after applying
                if 'coordination_partner' in robot_states[robot_index]:
                    del robot_states[robot_index]['coordination_partner']
        
        # Use stored stable offset if available (prevents zigzag)
        elif robot_index in robot_states and 'stable_offset' in robot_states[robot_index]:
            lateral_offset = robot_states[robot_index]['stable_offset']
            
            # Check if coordination is complete - ENHANCED CRITERIA
            if robot_index in robot_states and robot_states[robot_index].get('in_coordination', False):
                robot_pos = getRobotPosition(robot)
                coordination_complete = True
                
                # Check minimum coordination time (prevent premature return)
                coordination_start_time = robot_states[robot_index].get('coordination_start_time', 0)
                current_time = sim.SimTime
                min_coordination_time = 4.0  # Minimum 4 seconds of coordination
                
                if current_time - coordination_start_time < min_coordination_time:
                    coordination_complete = False
                else:
                    # Check distance to all other robots - INCREASED DISTANCE THRESHOLD
                    for other_robot in robots:
                        if other_robot != robot:
                            other_robot_index = get_robot_index(other_robot.Name)
                            other_pos = getRobotPosition(other_robot)
                            dist = vector_length(vector_subtract(robot_pos, other_pos))
                            
                            # Extended safe distance especially in intersection areas
                            safe_distance = 4500  # Increased from 3500
                            
                            # Check for intersection areas and increase safe distance further
                            nearby_pathways = 0
                            for other_pathway in app.Components:
                                if (other_pathway.Name.startswith('Pathway Area') or 
                                    other_pathway.Name.startswith('Idle Location')):
                                    pathway_distance = vector_length(vector_subtract(robot_pos, other_pathway.WorldPositionMatrix.P))
                                    if pathway_distance < 3000:  # Within intersection zone
                                        nearby_pathways += 1
                            
                            # Increase safe distance in intersection areas
                            if nearby_pathways > 2:
                                safe_distance = 5500  # Even larger distance in intersections
                            
                            # If still close to any robot, keep coordination
                            if dist < safe_distance:
                                coordination_complete = False
                                break
                            
                            # Additional check: ensure robots are moving in different directions
                            self_direction = get_movement_direction(robot, robot_index)
                            other_direction = get_movement_direction(other_robot, other_robot_index)
                            
                            if self_direction and other_direction:
                                # Calculate angle between directions
                                dot_product = self_direction.X * other_direction.X + self_direction.Y * other_direction.Y
                                # If robots are still moving towards each other (dot < 0), maintain coordination
                                if dot_product < -0.3 and dist < safe_distance * 1.2:  # 20% larger buffer for opposing movements
                                    coordination_complete = False
                                    break
                
                # Clear coordination state only when truly safe
                if coordination_complete:
                    robot_states[robot_index]['in_coordination'] = False
                    if 'stable_offset' in robot_states[robot_index]:
                        del robot_states[robot_index]['stable_offset']
                    if 'coordination_start_time' in robot_states[robot_index]:
                        del robot_states[robot_index]['coordination_start_time']
                    lateral_offset = vcVector.new(0, 0, 0)  # Return to center only when safe
        
        # Check for bypass scenarios (only if not in coordination)
        elif robot_index in robot_states and 'bypass_target' in robot_states[robot_index]:
            bypass_target_index = robot_states[robot_index]['bypass_target']
            lateral_offset = calculate_bypass_offset(robot, robot_index, bypass_target_index)
            del robot_states[robot_index]['bypass_target']
        
        # If not in any coordination mode, use minimal normal detection
        elif vector_length(lateral_offset) == 0 and not robot_states[robot_index].get('in_coordination', False):
            # Only use normal side-by-side if not already coordinating
            lateral_offset = calculate_side_by_side_offset(robot, robot_index, current_pathway)
        
        # Apply gradual offset to control points
        offset_control_points = []
        for point in control_points:
            offset_point = vector_add(point, lateral_offset)
            offset_control_points.append(offset_point)

        # Always clear move first to avoid interpolation error
        vehicle.clearMove()
        
        # Set vehicle properties only after clearing move
        vehicle.Acceleration = 300.0
        vehicle.Deceleration = 300.0
        vehicle.MaxSpeed = 800.0
        vehicle.Interpolation = 0.15

        # Add control points after setting all properties
        for point in offset_control_points:
            vehicle.addControlPoint(point)

        set_robot_property('Location', current_pathway.Name, robot_index)
        if i + 1 < len(pathways):
            set_robot_property('NextLocation', pathways[i + 1].Name, robot_index)
        else:
            set_robot_property('NextLocation', '', robot_index)

        robot_state['vehicle_initialized'] = True
        robot_state['elapsed_time'] = 0.0
        robot_state['total_move_time'] = vehicle.TotalTime

    # Progress through movement smoothly
    if robot_state['elapsed_time'] < robot_state['total_move_time']:
        # Check for stops but don't freeze permanently
        current_should_stop = get_robot_property_value('Stop', robot_index)
        if current_should_stop and robot_state['stop_start_time'] == 0:
            if vehicle:
                # Clear move to avoid interpolation errors when stopping
                vehicle.clearMove()
                vehicle.MaxSpeed = 0
                robot_state['vehicle_initialized'] = False
            return
        robot_state['elapsed_time'] += 0.1
    else:
        robot_state['current_pathway_index'] += 1
        robot_state['vehicle_initialized'] = False

    if robot_state['current_pathway_index'] >= len(pathways):
        robot_state['moving'] = False
        set_robot_property('Location', '', robot_index)
        set_robot_property('NextLocation', '', robot_index)

def OnSimulationUpdate(time):
    """Optimized collision management - reduced frequency to prevent stuttering and lag"""
    # Further reduce update frequency to prevent excessive collision checking and improve performance
    if not hasattr(OnSimulationUpdate, 'last_update'):
        OnSimulationUpdate.last_update = 0
    
    current_time = sim.SimTime
    if current_time - OnSimulationUpdate.last_update < 0.3:  # Update every 0.3 seconds for better performance
        return
    
    OnSimulationUpdate.last_update = current_time
    
    for robot in robots:
        robot_index = get_robot_index(robot.Name)
        robot_pos = getRobotPosition(robot)
        if robot_index in robot_states:
            vehicle = robot_states[robot_index]['vehicle']
            if vehicle:
                set_robot_property('MaxSpeed', int(vehicle.MaxSpeed), robot_index)
        
        # Apply collision detection less frequently
        check_proximity(robot, robot_index)

def OnReset():
    global robots, robot_states, cloned_robots, pathway_reservations, robot_planned_paths

    # Get the robot quantity before reset
    robot_quantity_prop = comp.getProperty('RobotQuantity')
    robot_quantity = robot_quantity_prop.Value if robot_quantity_prop and robot_quantity_prop.Value > 0 else 0

    # Detach components from all robots and clear component ownership
    for robot in robots:
        # No need to detach wagons since we're not using vehicle wagon system
        pass
    
    # Clear AttachedToRobot property from all components
    try:
        for component in app.Components:
            attached_to_prop = component.getProperty('AttachedToRobot')
            if attached_to_prop:
                attached_to_prop.Value = ''
            # No need to detach root features since we're not using root feature attachment
    except:
        pass

    # Reset RobotQuantity
    if robot_quantity_prop:
        robot_quantity_prop.Value = 0

    # Clear robots and states
    robots = []
    robot_states = {}
    
    # Clear reservation system
    pathway_reservations = {}
    robot_planned_paths = {}

    # Delete cloned robots
    for robot in cloned_robots:
        try: robot.delete()
        except: pass
    cloned_robots = []

    # Reset properties for each robot
    for i in range(1, min(robot_quantity, MAX_ROBOTS) + 1):
        for prop_name in property_names:
            unique_prop_name = '{0}{1}'.format(prop_name, i)
            prop = comp.getProperty(unique_prop_name)
            if prop:
                if prop.Type == VC_STRING:
                    prop.Value = ''
                elif prop.Type == VC_BOOLEAN:
                    prop.Value = False
                elif prop.Type == VC_INTEGER:
                    if prop_name == 'BatteryLevel':
                        prop.Value = 100
                    elif prop_name == 'Priority':
                        prop.Value = i  # Keep priority as robot index
                    else:
                        prop.Value = 0
'''


