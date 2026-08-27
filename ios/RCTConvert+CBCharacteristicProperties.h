#import <React/RCTConvert.h>
#import <CoreBluetooth/CoreBluetooth.h>

@interface RCTConvert (CBCharacteristicProperties)

+ (CBCharacteristicProperties)CBCharacteristicProperties:(id)json;

@end
