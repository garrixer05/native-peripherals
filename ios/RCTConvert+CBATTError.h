#import <React/RCTConvert.h>
#import <CoreBluetooth/CoreBluetooth.h>

@interface RCTConvert (CBATTError)

+ (CBATTError)CBATTError:(id)json;

@end
