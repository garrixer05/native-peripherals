#import <React/RCTConvert.h>
#import <CoreBluetooth/CoreBluetooth.h>

@interface RCTConvert (CBMutableCharacteristic)

+ (CBMutableCharacteristic *)CBMutableCharacteristic:(id)json;

@end
